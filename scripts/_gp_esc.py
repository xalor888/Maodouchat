import re

PATH = r"D:\Maodouchat\app\src\main\java\com\maodouchat\util\GroupPlayPolicy.kt"

with open(PATH, "r", encoding="utf-8") as f:
    lines = f.readlines()

# ---- helpers to insert ----
HELPER = (
    '    private fun esc(s: String): String = s.replace("|", "\\u0001").replace("^", "\\u0002")\n'
    '    private fun unesc(s: String): String = s.replace("\\u0001", "|").replace("\\u0002", "^")\n'
)

INT_SKIP = {"value", "sides", "dayStreak", "seconds", "secret", "max", "sec"}
LIST_LOCAL_SKIP = {"opts", "cells", "p"}

# format functions whose pre-'|' field is numeric/local-only OR that have no parse -> skip entirely
FORMAT_EXCLUDE = {
    "formatCountdown", "formatSpeedChallenge", "formatCountdownRace", "formatHotPotato",
    "formatNumberBomb", "formatNumberGuess", "formatDiceMessage",
    "formatWordChain", "formatCheckIn", "formatTruthPrompt", "formatAnonBox",
}

SUB_BRACE = re.compile(r"\$\{([^}]*)\}")
SUB_SIMPLE = re.compile(r"(?<![$\w\\])\$([A-Za-z_]\w*)")


def transform_format_template(tmpl):
    last_pipe = tmpl.rfind("|")

    def process(expr, start):
        e = expr.strip()
        base = re.split(r"[.\(\s\?]", e, maxsplit=1)[0].strip()
        if base.isupper() or base.endswith("PREFIX"):
            return None  # constant, skip
        if base in INT_SKIP:
            return None
        if base in LIST_LOCAL_SKIP:
            return None
        if "joinToString(\"^\"" in e:
            return None
        if "esc(" in e:
            return None
        if last_pipe >= 0 and start > last_pipe:
            return None  # trailing label -> skip (no corruption, avoids display regression)
        return "${esc(" + e + ")}"

    # collect all matches (brace + simple) from original, replace right-to-left
    matches = []
    for m in SUB_BRACE.finditer(tmpl):
        matches.append((m.start(), m.end(), process(m.group(1), m.start())))
    for m in SUB_SIMPLE.finditer(tmpl):
        matches.append((m.start(), m.end(), process(m.group(1), m.start())))
    matches = [x for x in matches if x[2] is not None]
    matches.sort(key=lambda x: x[0], reverse=True)
    out = tmpl
    for s, e, rep in matches:
        out = out[:s] + rep + out[e:]
    return out


# joinToString("^") whole-value escaping
JOIN_RE = re.compile(r"=\s*([\w.]+\.joinToString\(\"[^\"]*\"\)\s*\{[^}]*\})")


def transform_format_line(line):
    if "return \"" in line:
        # find the template string between the first " after return and the closing "
        idx = line.index("return \"") + len("return \"")
        end = line.index("\"", idx)
        tmpl = line[idx:end]
        new_tmpl = transform_format_template(tmpl)
        line = line[:idx] + new_tmpl + line[end:]
    return line


# parse transforms (uniform, applied to ALL parse functions)
SUB_BEFORE = re.compile(
    r"([\w.()]+\.(?:substring(?:Before|After)\('\|'(?:,\s*\"\")?\)\.)*substringBefore\('\|'\)(\.trim\(\))?)"
)
SPLIT_PIPE = re.compile(r"split\('\|'\)")


def transform_parse_line(line):
    line = SUB_BEFORE.sub(lambda m: "unesc(" + m.group(1) + ")", line)
    line = SPLIT_PIPE.sub("split('|').map { unesc(it) }", line)
    return line


out = []
current_format = None
current_parse = False
inserted = False

for line in lines:
    # helper insertion right after object declaration (independent of fun detection)
    if not inserted and "object GroupPlayPolicy {" in line:
        out.append(line)
        out.append(HELPER)
        inserted = True
        continue

    mfun = re.match(r"^\s*fun\s+(\w+)\(", line)
    if mfun:
        name = mfun.group(1)
        if name.startswith("format") and name not in FORMAT_EXCLUDE:
            current_format = name
            current_parse = False
        elif name.startswith("parse"):
            current_format = None
            current_parse = True
        else:
            current_format = None
            current_parse = False
    # lines not starting a fun keep current context (context persists across body)

    if current_format:
        # whole-value escape for ^-joined lists (applies to all lines of the function)
        line = JOIN_RE.sub(lambda m: "= esc(" + m.group(1) + ")", line)
        if "return \"" in line:
            line = transform_format_line(line)
    elif current_parse:
        line = transform_parse_line(line)

    out.append(line)

with open(PATH, "w", encoding="utf-8") as f:
    f.writelines(out)

print("done; helper inserted:", inserted)
