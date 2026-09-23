#!/usr/bin/env python3
"""
Assemble the Proxmox VE LogicModule suite into importable LogicMonitor module JSON.

The .groovy files under scripts/ are the source of truth. Each module body is prepended
with scripts/lib/pve_common.groovy so the script embedded in the JSON is self-contained,
which is what the Collector needs -- LogicMonitor has no include mechanism.

Dashboards are built here too, from dashboards/<Name>.py. They are a different
LogicMonitor resource -- imported through Dashboards > Add > From File, not My Module
Toolbox -- but they reference modules by name and datapoint, so they are validated in
the same pass: a module or datapoint rename that orphans a widget fails the build
rather than producing a dashboard of empty tiles.

Outputs:
  dist/<Module>.json           import via My Module Toolbox > Add > Import from file
  dist/scripts/<Module>.*.groovy   the assembled scripts, for pasting into the UI by hand
  dist/dashboards/<Name>.json  import via Dashboards > Add > From File

Usage:
  python build/build.py            build everything
  python build/build.py --check    validate only, write nothing (exit 1 on problems)
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import re
import shutil
import sys
from pathlib import Path

from groovylint import balance_check

sys.path.insert(0, str(Path(__file__).resolve().parent))

from dashboards import check_dashboard  # noqa: E402  (needs the path insert above)

ROOT = Path(__file__).resolve().parent.parent
SCRIPTS = ROOT / "scripts"
MODULES = ROOT / "modules"
DASHBOARDS = ROOT / "dashboards"
DIST = ROOT / "dist"
PREAMBLE = SCRIPTS / "lib" / "pve_common.groovy"

# LogicMonitor's datapoint dataType enum: 1 boolean, 2 byte, 3 short, 4 int, 5 long,
# 6 float, 7 double, 8 ulong. Everything here is a double; the API returns byte counts
# that exceed int range and percentages that need decimals.
DATA_TYPE_DOUBLE = 7

DATAPOINT_DEFAULTS = {
    # Metric type. LogicMonitor accepts gauge, counter, derive, status and compute here;
    # counters accumulated by the remote system are declared derive so LM computes a rate.
    "type": "gauge",
    "useValue": "output",
    "interpretMethod": "namevalue",
    "dataType": DATA_TYPE_DOUBLE,
    "maxDigits": 4,
    "min": "",
    "max": "",
    "triggerInterval": 0,
    "clearInterval": 0,
    "noData": "Do not trigger an alert",
}

# "conditional": true in a module definition marks a datapoint that the script may
# legitimately not print on a given run -- guest disk usage is LXC-only, and the cluster
# quorum datapoints are withheld on a standalone host. It is a build-time annotation, not
# a LogicMonitor field, so it is stripped before the datapoint reaches the export.
CONDITIONAL_KEY = "conditional"

# Every script in this suite now has a module definition. The PropertySource export
# schema was unverifiable for a long time, so addCategory_Proxmox_VE shipped as an
# assembled script to paste into the UI; two published exports settled it on 2026-09-22
# and it is an ordinary importable module like everything else.

# A module definition without "moduleType" is a DataSource, which is all this suite had
# until the TopologySource. The values here are ours, not LogicMonitor's.
DATASOURCE = "datasource"
TOPOLOGYSOURCE = "topologysource"
PROPERTYSOURCE = "propertysource"

# LogicMonitor's LogicModule type id for a TopologySource, read off a real export
# (VMware_vCenter_Cluster_Topology, type 9 -- an integer) rather than guessed. So was the
# rest of the TopologySource export shape in build_topologysource.
TOPOLOGY_TYPE_ID = 9

# collectionAttrs in a TopologySource export is not an object but a JSON-encoded *string*,
# and it carries every script slot, empty ones included. The first build emitted a bare
# {"scriptgroovy": ...} object; a portal refused it on 2026-09-23 with "non empty field
# value required", naming no field. scripttype "embed" is the one non-empty value besides
# the script itself, and the likeliest thing it wanted. Key order and the empty slots are
# copied from VMware_vCenter_Cluster_Topology and VMware_vSphere_VirtualMachine_Topology.
TOPOLOGY_EMPTY_ATTRS = (
    "linuxscript",
    "linuxcmdline",
    "windowscmdline",
    "manualConnections",
    "windowsscript",
    "properties",
)

# And the PropertySource ids, read off a published addERI_* export
# (addERI_VMware_VeloCloud): type 5 with propertySourceType 1, both integers, the script
# under "script" as {type, content} rather than under collectionAttrs, and group "ERI".
# That export is also what retired this repo's long-standing note that a PropertySource
# export schema could not be verified -- but it is an *ERI* PropertySource, and whether
# propertySourceType 1 is right for a plain one like addCategory_Proxmox_VE is still
# unknown, which is why that one continues to ship as a script to paste.
# type 5 is every PropertySource. What separates an ERI one from a plain one is what
# the plain one LEAVES OUT: addERI_VMware_VeloCloud carries propertySourceType 1 and a
# collectionInterval, and addCategory_VMwareHorizonConnectionServer carries neither.
# So "eri": true in a definition adds both, and omitting it adds neither -- guessing in
# either direction would produce a module that imports and then does the wrong thing.
PROPERTYSOURCE_TYPE_ID = 5
ERI_PROPERTYSOURCE_SUBTYPE = 1
ERI_GROUP = "ERI"

# TopologySource scripts are the one place this suite depends on Collector-only classes:
# LogicMonitor's own topology scripts do all their work through com.logicmonitor.mod
# .Snippets, and reimplementing ERI normalisation, the blocked-key list and the output
# format would mean inventing a format -- the exact mistake that cost thirteen dashboard
# widgets. The trade is that groovyc and the harness cannot touch these, so they are
# emitted into a subdirectory that the compile job's dist/scripts/*.glob does not reach.
COLLECTOR_ONLY_DIR = "collector-only"

# Datapoint names LogicMonitor refuses as reserved words. Nothing local catches this: the
# build, compile and harness all pass, and the portal rejects the module on import. The
# list is what a portal has actually refused, not a guess at LogicMonitor's full set --
# "In" (CephOSD) on 2026-09-21. Compared case-insensitively. Add any other name the
# portal rejects here, rather than renaming it by hand in dist/.
RESERVED_DATAPOINT_NAMES = {"in"}

EMIT_RE = re.compile(r"""pveEmit\(\s*[^,]+,\s*'([A-Za-z0-9_]+)'""")

# BatchScript post-processor keys are scoped to the instance with this token; see
# expand_datapoint.
WILDVALUE_PREFIX = "##WILDVALUE##."


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def assemble(body_name: str) -> str:
    """Prepend the shared preamble to a module body."""
    body = read(SCRIPTS / body_name)
    return read(PREAMBLE).rstrip() + "\n\n" + ("-" * 0) + body.lstrip("\n")


def expand_datapoint(dp: dict, batch: bool) -> dict:
    out = dict(DATAPOINT_DEFAULTS)
    out.update(dp)
    out.pop(CONDITIONAL_KEY, None)
    # The post-processor key is the name printed by the script; they are always the same
    # here, and keeping them coupled is what makes the drift check below meaningful.
    #
    # BatchScript is the exception. One execution prints every instance into a single
    # stream, so a bare key matches nothing -- the key has to be scoped to the instance,
    # and LogicMonitor does that by substituting ##WILDVALUE## per instance at poll time.
    # Get this wrong and discovery, collection and the script output all look correct
    # while every datapoint on every instance reads No Data.
    out.setdefault("interpretExpr", (WILDVALUE_PREFIX if batch else "") + out["name"])
    return out


# Keys every module definition must carry. Checked before anything is read off the
# definition, so a typo or an omission reports as a validation problem naming the file
# rather than as a KeyError traceback from somewhere inside the build.
REQUIRED_KEYS = [
    "name",
    "displayedAs",
    "description",
    "appliesTo",
    "collectionMethod",
    "collectionInterval",
    "collectScript",
    "datapoints",
]


# A TopologySource has no datapoints and no collection method to declare -- it prints
# edges, not metrics -- so it answers to a shorter list than REQUIRED_KEYS.
TOPOLOGY_REQUIRED_KEYS = [
    "name",
    "description",
    "appliesTo",
    "collectionIntervalSec",
    "collectScript",
]

PROPERTYSOURCE_REQUIRED_KEYS = [
    "name",
    "description",
    "appliesTo",
    "collectScript",
]


def module_type(defn: dict) -> str:
    return defn.get("moduleType", DATASOURCE)


def check_definition(path: Path, defn: dict) -> list[str]:
    kind = module_type(defn)
    if kind not in (DATASOURCE, TOPOLOGYSOURCE, PROPERTYSOURCE):
        return [f"{path.name}: unknown moduleType {kind!r}"]
    required = {
        TOPOLOGYSOURCE: TOPOLOGY_REQUIRED_KEYS,
        PROPERTYSOURCE: PROPERTYSOURCE_REQUIRED_KEYS,
    }.get(kind, REQUIRED_KEYS)
    missing = [key for key in required if key not in defn]
    if missing:
        return [f"{path.name}: module definition is missing {', '.join(missing)}"]
    if kind != DATASOURCE and defn.get("datapoints"):
        return [f"{path.name}: a {kind} declares no datapoints"]
    return []


def build_propertysource(defn: dict) -> tuple[dict, dict[str, str]]:
    """
    Return (module JSON, {output filename: assembled script}) for an ERI PropertySource.

    Collector-only for the same reason the TopologySource is: LogicMonitor's own addERI_*
    modules build the ERI output through lm.topo's emitEri and printEriArray, which also
    apply topo.namespace and topo.blacklist. Writing that JSON by hand would mean guessing
    a format, so the assembled script goes where groovyc and the harness cannot reach it.
    """
    name = defn["name"]
    eri = bool(defn.get("eri"))
    # Only the ERI ones reach for Collector-only classes; a plain PropertySource is
    # ordinary Groovy and stays where groovyc and the harness can see it.
    filename = f"{COLLECTOR_ONLY_DIR}/{name}.groovy" if eri else f"{name}.groovy"
    script = assemble(defn["collectScript"])
    module = {
        "name": name,
        "group": defn.get("group", ERI_GROUP if eri else "Proxmox VE"),
        "description": defn["description"],
        "appliesTo": defn["appliesTo"],
        "technicalNotes": defn.get("technicalNotes", ""),
        "searchKeywords": defn.get("searchKeywords", ""),
        "script": {"type": "groovy", "content": script},
        "type": PROPERTYSOURCE_TYPE_ID,
    }
    if eri:
        module["propertySourceType"] = ERI_PROPERTYSOURCE_SUBTYPE
        module["collectionInterval"] = int(defn["collectionInterval"])
    return module, {filename: script}


def build_topologysource(defn: dict) -> tuple[dict, dict[str, str]]:
    """
    Return (module JSON, {output filename: assembled script}) for a TopologySource.

    Field names and shape come from a real portal export rather than from the API model:
    collectionAttrs is a JSON-encoded string carrying the Groovy under "scriptgroovy",
    scripttype "embed" and the empty script slots; the interval is integer seconds, and
    type is the integer 9. The registryMetadata and integrationMetadata blocks a
    published module carries are Exchange lineage and are deliberately not fabricated
    here -- see this module's UNVERIFIED note.
    """
    name = defn["name"]
    filename = f"{COLLECTOR_ONLY_DIR}/{name}.topo.groovy"
    script = assemble(defn["collectScript"])
    module = {
        "name": name,
        "group": defn.get("group", "Proxmox VE"),
        "description": defn["description"],
        "appliesTo": defn["appliesTo"],
        "technicalNotes": defn.get("technicalNotes", ""),
        "searchKeywords": defn.get("searchKeywords", ""),
        "collectionMethod": "script",
        "collectionIntervalSec": int(defn["collectionIntervalSec"]),
        "collectionAttrs": json.dumps(
            {"scriptgroovy": script, "scripttype": "embed"}
            | {key: "" for key in TOPOLOGY_EMPTY_ATTRS}
        ),
        "type": TOPOLOGY_TYPE_ID,
    }
    return module, {filename: script}


def build_module(defn: dict) -> tuple[dict, dict[str, str]]:
    """Return (module JSON, {output filename: assembled script})."""
    name = defn["name"]
    scripts: dict[str, str] = {}

    collect = assemble(defn["collectScript"])
    scripts[f"{name}.collect.groovy"] = collect

    module = {
        "name": name,
        "displayedAs": defn["displayedAs"],
        "description": defn["description"],
        "appliesTo": defn["appliesTo"],
        "technicalNotes": defn.get("technicalNotes", ""),
        "searchKeywords": defn.get("searchKeywords", ""),
        "group": defn.get("group", ""),
        "collectionMethod": defn["collectionMethod"],
        "collectionInterval": defn["collectionInterval"],
        "collectionAttrs": {"type": "groovy", "content": collect},
        "multiInstance": defn.get("multiInstance", False),
        "useWildValueAsUniqueIdentifier": defn.get("useWildValueAsUniqueIdentifier", False),
        "dataSourceType": 1,
        "type": 0,
        "datapoints": [
            expand_datapoint(dp, defn["collectionMethod"] == "batchscript")
            for dp in defn["datapoints"]
        ],
    }

    if "discoveryScript" in defn:
        discovery = assemble(defn["discoveryScript"])
        scripts[f"{name}.ad.groovy"] = discovery
        module["activeDiscovery"] = {
            "enabled": True,
            "discoveryMethod": "ad_script",
            "params": {"type": "groovy", "content": discovery},
            "discoveryInterval": defn.get("discoveryInterval", "1440m"),
            # Instances are never auto-deleted: a collection script that exits non-zero
            # during a Proxmox outage must not cause LogicMonitor to tear down history.
            "deleteInactiveInstances": False,
            "autoDeleteInstances": False,
            "disableDiscoveredInstances": False,
            "groupMethod": "none",
            "filters": [],
        }

    return module, scripts


def check_module(defn: dict, module: dict) -> list[str]:
    """Cross-check declared datapoints against what the collection script actually prints."""
    name = defn["name"]
    problems: list[str] = []

    emitted = set(EMIT_RE.findall(read(SCRIPTS / defn["collectScript"])))
    declared = {dp["name"] for dp in module["datapoints"]}
    conditional = {dp["name"] for dp in defn["datapoints"] if dp.get(CONDITIONAL_KEY)}

    for reserved in sorted(n for n in declared if n.lower() in RESERVED_DATAPOINT_NAMES):
        problems.append(f"{name}: datapoint '{reserved}' is a name LogicMonitor reserves")
    for missing in sorted(emitted - declared):
        problems.append(f"{name}: script prints '{missing}' but no datapoint declares it")
    for unfilled in sorted(declared - emitted - conditional):
        problems.append(f"{name}: datapoint '{unfilled}' is declared but never printed")

    if module["collectionMethod"] == "batchscript" and not module.get("multiInstance"):
        problems.append(f"{name}: batchscript collection requires multiInstance")

    # The post-processor key must match the shape of the output the script actually
    # prints. A mismatch here is invisible everywhere else: the build passes, the
    # scripts compile, the harness is green, Test Collection Script shows correct
    # output in the portal, and every datapoint still reads No Data.
    batch = module["collectionMethod"] == "batchscript"
    for dp in module["datapoints"]:
        scoped = dp["interpretExpr"].startswith(WILDVALUE_PREFIX)
        if batch and not scoped:
            problems.append(
                f"{name}: datapoint '{dp['name']}' has post-processor key "
                f"'{dp['interpretExpr']}', but batchscript output is prefixed with the "
                f"instance id -- expected '{WILDVALUE_PREFIX}{dp['name']}'"
            )
        elif not batch and scoped:
            problems.append(
                f"{name}: datapoint '{dp['name']}' scopes its post-processor key with "
                f"{WILDVALUE_PREFIX} but this is not a batchscript module"
            )
    if module.get("multiInstance") and "activeDiscovery" not in module:
        problems.append(f"{name}: multiInstance requires an Active Discovery script")

    return problems


def load_dashboard(path: Path):
    """Import dashboards/<Name>.py and return its build() result."""
    spec = importlib.util.spec_from_file_location(f"dashboard_{path.stem}", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    if not hasattr(module, "build"):
        raise AttributeError(f"{path.name} defines no build()")
    return module.build()


def module_datapoint_index(definitions: list[Path]) -> dict[str, set[str]]:
    """
    Map "<displayedAs> (<name>)" -> that module's datapoint names.

    This is what lets the build refuse a dashboard whose widgets point at a module
    or a datapoint that no longer exists. A dashboard has no equivalent of the
    datapoint drift check, and a widget aimed at a renamed datapoint renders an
    empty tile rather than an error, so the check has to live here.
    """
    index: dict[str, set[str]] = {}
    for path in definitions:
        defn = json.loads(read(path))
        if "displayedAs" in defn and "name" in defn:
            key = f"{defn['displayedAs']} ({defn['name']})"
            index[key] = {dp["name"] for dp in defn.get("datapoints", [])}
    return index


def build_dashboards(
    definitions: list[Path],
) -> tuple[list[tuple[str, dict]], list[str]]:
    """Return ([(name, rendered dashboard)], problems)."""
    problems: list[str] = []
    built: list[tuple[str, dict]] = []
    if not DASHBOARDS.is_dir():
        return built, problems

    index = module_datapoint_index(
        [p for p in definitions
         if module_type(json.loads(read(p))) == DATASOURCE]
    )
    for path in sorted(DASHBOARDS.glob("*.py")):
        try:
            dashboard = load_dashboard(path).render()
        except Exception as exc:  # a broken definition must name its own file
            problems.append(f"{path.name}: {type(exc).__name__}: {exc}")
            continue
        problems.extend(check_dashboard(path.name, dashboard, index))
        built.append((path.stem, dashboard))
    return built, problems


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="validate without writing")
    args = parser.parse_args()

    definitions = sorted(MODULES.glob("*.json"))
    if not definitions:
        print(f"No module definitions found in {MODULES}", file=sys.stderr)
        return 1

    problems: list[str] = []
    built: list[tuple[dict, dict[str, str]]] = []

    for path in definitions:
        defn = json.loads(read(path))
        incomplete = check_definition(path, defn)
        if incomplete:
            problems.extend(incomplete)
            continue
        if module_type(defn) == TOPOLOGYSOURCE:
            module, scripts = build_topologysource(defn)
        elif module_type(defn) == PROPERTYSOURCE:
            module, scripts = build_propertysource(defn)
        else:
            module, scripts = build_module(defn)
            problems.extend(check_module(defn, module))
        for filename, content in scripts.items():
            problems.extend(balance_check(filename, content))
        built.append((module, scripts))

    dashboards, dashboard_problems = build_dashboards(definitions)
    problems.extend(dashboard_problems)

    if problems:
        print("Validation failed:\n", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1

    if args.check:
        print(
            f"OK: {len(built)} modules and {len(dashboards)} dashboards validated"
        )
        return 0

    # Wipe dist/ rather than writing over it. The build used to leave whatever it had
    # written before, so a script that was renamed, moved or dropped stayed behind --
    # and a stale one still compiles, still looks like output, and is still there to be
    # imported. addERI_Proxmox_VE left exactly that trap behind when it moved into
    # collector-only/. dist/ is generated and gitignored, so there is nothing here to
    # preserve; --check returns above without reaching this.
    if DIST.exists():
        shutil.rmtree(DIST)
    (DIST / "scripts").mkdir(parents=True, exist_ok=True)
    for module, scripts in built:
        target = DIST / f"{module['name']}.json"
        target.write_text(json.dumps(module, indent=2) + "\n", encoding="utf-8")
        for filename, content in scripts.items():
            out = DIST / "scripts" / filename
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text(content, encoding="utf-8")
        if "datapoints" not in module:
            if module["type"] == PROPERTYSOURCE_TYPE_ID:
                kind = "propertysource"
                note = ("eri, Collector-only" if "propertySourceType" in module
                        else "category")
            else:
                kind = "topologysource"
                note = "Collector-only"
            print(f"  {module['name']:34} {kind}  ({note})")
            continue
        dp_count = len(module["datapoints"])
        print(f"  {module['name']:34} {module['collectionMethod']:12} {dp_count:2} datapoints")

    if dashboards:
        (DIST / "dashboards").mkdir(parents=True, exist_ok=True)
        for name, dashboard in dashboards:
            target = DIST / "dashboards" / f"{name}.json"
            target.write_text(
                json.dumps(dashboard, indent=2) + "\n", encoding="utf-8"
            )
            widget_count = len(dashboard["widgets"])
            print(f"  {name:34} dashboard    {widget_count:2} widgets")

    print(
        f"\nBuilt {len(built)} modules and {len(dashboards)} dashboards "
        f"into {DIST.relative_to(ROOT)}/"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
