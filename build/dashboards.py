#!/usr/bin/env python3
"""
Widget builders and a validator for LogicMonitor dashboard exports.

A dashboard is a different resource from a LogicModule: it is imported through
Dashboards > Add > From File rather than My Module Toolbox, and nothing in the module
build touches it. This file is the shared half; the dashboards themselves live in
dashboards/<Name>.py, one module each, exposing build() -> dict.

WHERE THE SCHEMA COMES FROM
---------------------------
Every key name, every enum value and every nesting shape below was taken from
LogicMonitor's own published dashboard exports rather than guessed:

    https://github.com/logicmonitor/dashboards  (Apache-2.0)
    Virtualization/Hyper-V.json     cgraph, dynamicTable, noc, alert
    Virtualization/Nutanix.json     bigNumber

That matters for the same reason it matters in build.py: an export format that is
almost right imports cleanly and then renders nothing, and there is no local check that
can tell you. REFERENCE_KEYS and the enum tuples below record what those files actually
contain, so check_dashboard can refuse anything this project invented on its own.

THE ONE DELIBERATE DEPARTURE
----------------------------
LogicMonitor's VMware and Hyper-V dashboards legend on ##HOSTNAME##, because those
suites give every hypervisor its own resource. This suite is cluster-scoped: a single
Proxmox resource carries every node, guest and storage object as *instances* of a
handful of modules. So widgets here legend on ##INSTANCE## and glob instances with "*",
which is what makes one tile show the whole cluster. Legend on hostname instead and
every series on the graph gets the same label.
"""

from __future__ import annotations

import re

# Widget config key sets, verbatim from the reference exports. A widget this project
# builds must match one of these exactly -- an unexpected key is as likely to break the
# import as a missing one, and neither shows up as an error in the portal.
REFERENCE_KEYS: dict[str, set[str]] = {
    "cgraph": {
        "description", "displaySettings", "graphInfo", "interval", "name", "theme",
        "timescale", "type", "version",
    },
    "dynamicTable": {
        "columns", "dataSourceFullName", "description", "displaySettings", "forecast",
        "interval", "name", "rows", "sortOrder", "theme", "timescale", "topX", "type",
        "version",
    },
    "bigNumber": {
        "bigNumberInfo", "description", "displaySettings", "interval", "name", "theme",
        "timescale", "type", "version",
    },
    "noc": {
        "ackChecked", "description", "displayColumn", "displayCriticalAlert",
        "displayErrorAlert", "displaySettings", "displayWarnAlert", "interval", "items",
        "name", "sdtChecked", "sortBy", "theme", "timescale", "type", "version",
    },
    "alert": {
        "description", "displaySettings", "filters", "interval", "name", "theme",
        "timescale", "type", "version",
    },
}

# Enum vocabularies observed in the reference exports. Anything outside these is an
# invention, and inventions are what fail to import.
THEMES = ("newSolidDarkBlue", "newBorderDarkBlue")
TIMESCALES = ("8hour", "12hour", "day", "2days", "7days")
DISPLAY_TYPES = ("line", "area", "stack")
# LogicMonitor severity levels. Only warn and error appear in the reference dashboards'
# colorThresholds, so only those are offered here rather than assuming critical works.
LEVEL_WARN = 2
LEVEL_ERROR = 3

DEFAULT_THEME = THEMES[0]
GROUP_TOKEN = "##defaultResourceGroup##"
INSTANCE_LEGEND = "##INSTANCE##"

# The grid every LogicMonitor dashboard lays out on.
GRID_COLUMNS = 12


def module_ref(displayed_as: str, name: str) -> str:
    """
    How a widget addresses a LogicModule: "<displayedAs> (<name>)".

    This is a plain string inside the dashboard JSON with nothing enforcing it, so
    renaming a module or its display name breaks every widget that references it and
    reports nothing. check_dashboard resolves these against modules/*.json for exactly
    that reason.
    """
    return f"{displayed_as} ({name})"


def parse_module_ref(ref: str) -> str | None:
    """The bare module name out of a "<displayedAs> (<name>)" reference."""
    match = re.search(r"\(([^)]+)\)$", ref)
    return match.group(1) if match else None


class Dashboard:
    """Accumulates widgets, then renders the export dict."""

    def __init__(self, name: str, description: str, group: str,
                 resource_group: str = "*", theme: str = DEFAULT_THEME) -> None:
        self.name = name
        self.description = description
        self.group = group
        self.resource_group = resource_group
        self.theme = theme
        self.widgets: list[dict] = []

    # ------------------------------------------------------------------ placement

    def _place(self, row: int, col: int, sizex: int, sizey: int) -> dict:
        return {"col": col, "sizex": sizex, "row": row, "sizey": sizey}

    def _add(self, row: int, col: int, sizex: int, sizey: int, config: dict) -> None:
        self.widgets.append({"position": self._place(row, col, sizex, sizey),
                             "config": config})

    # -------------------------------------------------------------------- pieces

    def series(self, module: str, datapoint: str, legend: str = INSTANCE_LEGEND,
               display: str = "line", colour: str = "Auto",
               option: str = "custom") -> dict:
        """One series on a cgraph."""
        return {
            "dataPointName": datapoint,
            "instanceName": {"isGlob": True, "value": "*"},
            "dataSourceFullName": module,
            "consolidateFunction": "average",
            "display": {"color": colour, "legend": legend, "type": display,
                        "option": option},
            "name": datapoint,
            "aggregateFunction": "SUM",
            "deviceDisplayName": {"isGlob": True, "value": "*"},
            "deviceGroupFullPath": {"isGlob": True, "value": GROUP_TOKEN},
        }

    def column(self, datapoint: str, label: str, display_type: str = "percent",
               minimum=0, maximum=100, warn=None, error=None, unit: str = "",
               rounding: int = 2) -> dict:
        """One column of a dynamicTable."""
        thresholds: list[dict] = []
        if warn is not None:
            thresholds.append({"level": LEVEL_WARN, "threshold": warn, "relation": ">="})
        if error is not None:
            thresholds.append({"level": LEVEL_ERROR, "threshold": error,
                               "relation": ">="})
        return {
            "rpn": "",
            "dataPointName": datapoint,
            "displayType": display_type,
            "minValue": minimum,
            "unitLabel": unit,
            "maxValue": maximum,
            "colorThresholds": thresholds or None,
            "columnName": label,
            "enableForecast": False,
            "roundingDecimal": rounding,
        }

    # ------------------------------------------------------------------- widgets

    def graph(self, row: int, col: int, sizex: int, sizey: int, name: str,
              series: list[dict], vertical_label: str, maximum=100, top_x: int = 10,
              timescale: str = "8hour", description: str = "") -> None:
        self._add(row, col, sizex, sizey, {
            "displaySettings": {},
            "name": name,
            "description": description,
            "theme": self.theme,
            "interval": 3,
            "graphInfo": {
                "virtualDataPoints": [],
                "minValue": 0,
                "topX": top_x,
                "maxValue": maximum,
                "dataPoints": series,
                "verticalLabel": vertical_label,
                "aggregate": False,
                "desc": True,
                "scaleUnit": 1000,
                "globalConsolidateFunction": "VaST",
            },
            "type": "cgraph",
            "timescale": timescale,
            "version": 2,
        })

    def table(self, row: int, col: int, sizex: int, sizey: int, name: str, module: str,
              columns: list[dict], top_x: int = 25, timescale: str = "day",
              description: str = "") -> None:
        display_columns = [{
            "visible": True,
            "columnLabel": "Instance",
            "columnSize": 220,
            "columnKey": "device-name-1452842526600",
        }]
        for index, column in enumerate(columns):
            display_columns.append({
                "visible": True,
                "columnLabel": column["columnName"],
                "columnSize": 140,
                "columnKey": str(index),
            })
        self._add(row, col, sizex, sizey, {
            "displaySettings": {"columns": display_columns, "pageSize": "10"},
            "columns": columns,
            "description": description,
            "forecast": {"severity": "warn", "confidence": 70,
                         "timeRange": "Last 30 days", "algorithm": "Linear"},
            "type": "dynamicTable",
            "rows": [{
                "instanceName": "*",
                "label": INSTANCE_LEGEND,
                "deviceDisplayName": "*",
                "groupFullPath": GROUP_TOKEN,
            }],
            "version": 2,
            "topX": top_x,
            "dataSourceFullName": module,
            "sortOrder": "descending",
            "name": name,
            "theme": self.theme,
            "interval": 3,
            "timescale": timescale,
        })

    def numbers(self, row: int, col: int, sizex: int, sizey: int, name: str,
                items: list[tuple], description: str = "") -> None:
        """items: (module, datapoint, label, rounding) per figure, in display order."""
        data_points, number_items = [], []
        for position, (module, datapoint, label, rounding) in enumerate(items, start=1):
            data_points.append({
                "dataPointName": datapoint,
                "instanceName": "*",
                "dataSourceFullName": module,
                "name": datapoint,
                "aggregateFunction": "SUM",
                "deviceGroupFullPath": GROUP_TOKEN,
                "deviceDisplayName": "*",
            })
            number_items.append({
                "dataPointName": datapoint,
                "useCommaSeparators": False,
                "bottomLabel": "",
                "rounding": rounding,
                "position": position,
                "rightLabel": label,
                "colorThresholds": None,
            })
        self._add(row, col, sizex, sizey, {
            "bigNumberInfo": {
                "virtualDataPoints": [],
                "counters": [],
                "dataPoints": data_points,
                "bigNumberItems": number_items,
            },
            "displaySettings": {},
            "name": name,
            "description": description,
            "theme": self.theme,
            "interval": 3,
            "type": "bigNumber",
            "timescale": "day",
            "version": 2,
        })

    def alert_status(self, row: int, col: int, sizex: int, sizey: int, name: str,
                     module_glob: str, group_by: str = "instance") -> None:
        """The noc tile: a coloured grid of current alert state."""
        self._add(row, col, sizex, sizey, {
            "ackChecked": True,
            "displaySettings": {"showTypeIcon": True, "displayAs": "table"},
            "displayWarnAlert": True,
            "description": "",
            "type": "noc",
            "version": 2,
            "displayErrorAlert": True,
            "displayColumn": 2,
            "name": name,
            "displayCriticalAlert": True,
            "theme": self.theme,
            "interval": 3,
            "sortBy": "alertSeverity",
            "timescale": "day",
            "sdtChecked": True,
            "items": [{
                "dataPointName": "*",
                "instanceName": "*",
                "name": INSTANCE_LEGEND,
                "dataSourceDisplayName": module_glob,
                "groupBy": group_by,
                "type": "device",
                "deviceGroupFullPath": GROUP_TOKEN,
                "deviceDisplayName": "*",
            }],
        })

    def alert_table(self, row: int, col: int, sizex: int, sizey: int, name: str,
                    module_glob: str) -> None:
        """The alert list. Column set and visibility copied from the Hyper-V export."""
        columns = [
            ("Severity", "alert-severity", False),
            ("Began", "alert-began", True),
            ("Device/Website", "alert-device", True),
            ("LogicModule", "alert-datasource", True),
            ("Instance", "alert-datasource-instance", True),
            ("Datapoint", "alert-datapoint", True),
            ("Value", "alert-value", True),
            ("Effective Thresholds", "alert-thresholds", True),
            ("Group", "alert-group", False),
            ("Notes", "alert-notes", True),
            ("Acknowledged By", "alert-acked-by", False),
            ("Acknowledged On", "alert-acked-on", True),
            ("Cleared On", "alert-cleared-on", False),
            ("In SDT", "alert-in-sdt", False),
            ("Alert Rule", "alert-rule-name", False),
            ("Escalation Chain", "alert-escalation-chain", False),
            ("Instance Description", "alert-datasource-instance-description", False),
            ("Full Path", "alert-full-path", False),
        ]
        self._add(row, col, sizex, sizey, {
            "displaySettings": {
                "isShowAll": False,
                "showFilter": False,
                "columns": [{"visible": visible, "columnLabel": label,
                             "columnKey": key} for label, key, visible in columns],
                "playSound": {
                    "criticalAlertAudioFileName": "",
                    "errorAlertAudioFileName": "",
                    "warningAlertAudioFileName": "",
                    "shouldPlay": False,
                },
                "fontsize": "normal-font",
                "sort": "-startEpoch",
            },
            "name": name,
            "description": "",
            "theme": self.theme,
            "interval": 3,
            "filters": {
                "severity": "", "sdted": "all", "chain": "", "instance": "",
                "dataPoint": "", "rule": "", "acked": "all",
                "dependencyRoutingState": "", "dependencyRole": "", "host": "",
                "keyword": "", "dataSource": module_glob, "cleared": "no",
                # The group filter is URL-encoded in the reference exports; ##token##
                # becomes %23%23token%23%23.
                "group": "%23%23defaultResourceGroup%23%23*",
            },
            "type": "alert",
            "timescale": "day",
            "version": 2,
        })

    # -------------------------------------------------------------------- render

    def render(self) -> dict:
        return {
            # The portal version that produced the reference export this format was
            # taken from. LogicMonitor stamps it on export and reads it on import.
            "santabaRelease": 169,
            "widgetTokens": [{"name": "defaultResourceGroup",
                              "value": self.resource_group}],
            "name": self.name,
            "description": self.description,
            "overwriteGroupFields": False,
            "widgetsConfigVersion": 2,
            "type": "dashboard",
            "widgets": self.widgets,
            "version": 2,
            "group": {"name": self.group, "fullPath": self.group, "description": ""},
        }


# ------------------------------------------------------------------- validation

def check_dashboard(label: str, dashboard: dict,
                    modules: dict[str, set[str]]) -> list[str]:
    """
    Cross-check a rendered dashboard against the module definitions and the reference
    schema. `modules` maps "<displayedAs> (<name>)" to that module's datapoint names.

    The expensive failure this guards against is a module or datapoint rename: the
    dashboard keeps importing, the widgets keep rendering, and every one of them is
    empty. Nothing in the portal reports it.
    """
    problems: list[str] = []
    references: list[tuple[str, str, str]] = []

    for widget in dashboard["widgets"]:
        config = widget["config"]
        kind = config.get("type")
        name = config.get("name", "<unnamed>")

        if kind not in REFERENCE_KEYS:
            problems.append(f"{label}: {name!r} has widget type {kind!r}, which appears "
                            f"in no reference export")
            continue
        if set(config) != REFERENCE_KEYS[kind]:
            extra = sorted(set(config) - REFERENCE_KEYS[kind])
            missing = sorted(REFERENCE_KEYS[kind] - set(config))
            problems.append(f"{label}: {name!r} ({kind}) key set differs from the "
                            f"reference export -- extra={extra} missing={missing}")
        if config.get("theme") not in THEMES:
            problems.append(f"{label}: {name!r} theme {config.get('theme')!r} is not one "
                            f"the reference exports use")
        if config.get("timescale") not in TIMESCALES:
            problems.append(f"{label}: {name!r} timescale {config.get('timescale')!r} is "
                            f"not one the reference exports use")

        if kind == "cgraph":
            for series in config["graphInfo"]["dataPoints"]:
                references.append((series["dataSourceFullName"],
                                   series["dataPointName"], name))
                if series["display"]["type"] not in DISPLAY_TYPES:
                    problems.append(f"{label}: {name!r} display type "
                                    f"{series['display']['type']!r} is not one the "
                                    f"reference exports use")
        elif kind == "dynamicTable":
            for column in config["columns"]:
                references.append((config["dataSourceFullName"],
                                   column["dataPointName"], name))
        elif kind == "bigNumber":
            for series in config["bigNumberInfo"]["dataPoints"]:
                references.append((series["dataSourceFullName"],
                                   series["dataPointName"], name))

        if set(widget["position"]) != {"col", "sizex", "row", "sizey"}:
            problems.append(f"{label}: {name!r} has malformed position keys")

    for module, datapoint, where in references:
        if module not in modules:
            problems.append(f"{label}: {where!r} references unknown module {module!r}")
        elif datapoint not in modules[module]:
            problems.append(f"{label}: {where!r} references {module}, which declares no "
                            f"datapoint {datapoint!r}")

    problems.extend(_check_grid(label, dashboard["widgets"]))
    return problems


def _check_grid(label: str, widgets: list[dict]) -> list[str]:
    """Overlapping widgets render on top of each other rather than erroring."""
    problems: list[str] = []
    occupied: dict[tuple[int, int], str] = {}
    for widget in widgets:
        position = widget["position"]
        name = widget["config"].get("name", "<unnamed>")
        if position["col"] + position["sizex"] - 1 > GRID_COLUMNS:
            problems.append(f"{label}: {name!r} overflows the {GRID_COLUMNS}-column "
                            f"grid (col {position['col']} + {position['sizex']})")
        for row in range(position["row"], position["row"] + position["sizey"]):
            for col in range(position["col"], position["col"] + position["sizex"]):
                if (row, col) in occupied:
                    problems.append(f"{label}: {name!r} overlaps "
                                    f"{occupied[(row, col)]!r} at row {row} col {col}")
                occupied[(row, col)] = name
    return problems
