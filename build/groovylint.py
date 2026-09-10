"""Static checks for assembled Groovy, for when no Groovy runtime is available.

These scripts cannot run outside a LogicMonitor Collector, and a dev machine rarely has
Groovy installed, so a whole class of typo -- an unbalanced bracket -- would otherwise
stay invisible until the Collector rejected the script. This is not a substitute for
compiling; it catches the one mistake that is both common and silent.
"""

from __future__ import annotations

PAIRS = {")": "(", "]": "[", "}": "{"}
QUOTES = "'\""


def strip_code(text: str) -> str:
    """Blank out comments and string literals so bracket counting sees only code."""
    out: list[str] = []
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""

        if ch == "/" and nxt == "/":
            i = text.find("\n", i)
            if i == -1:
                break
        elif ch == "/" and nxt == "*":
            end = text.find("*/", i + 2)
            i = n if end == -1 else end + 2
        elif ch in QUOTES:
            quote = ch
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == quote:
                    i += 1
                    break
                if text[i] == "\n":
                    # Unterminated single-line string; let the newline through so line
                    # numbers stay accurate rather than swallowing the rest of the file.
                    break
                i += 1
        else:
            out.append(ch)
            i += 1
    return "".join(out)


def balance_check(label: str, text: str) -> list[str]:
    """Return a list of bracket-balance problems in a Groovy source string."""
    stack: list[tuple[str, int]] = []
    problems: list[str] = []

    for line_no, line in enumerate(strip_code(text).splitlines(), 1):
        for ch in line:
            if ch in "([{":
                stack.append((ch, line_no))
            elif ch in PAIRS:
                if not stack:
                    problems.append(f"{label}: unexpected '{ch}' on line {line_no}")
                elif stack[-1][0] != PAIRS[ch]:
                    open_ch, open_line = stack.pop()
                    problems.append(
                        f"{label}: '{ch}' on line {line_no} closes "
                        f"'{open_ch}' opened on line {open_line}"
                    )
                else:
                    stack.pop()

    for open_ch, open_line in stack:
        problems.append(f"{label}: '{open_ch}' opened on line {open_line} is never closed")

    return problems
