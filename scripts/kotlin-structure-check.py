#!/usr/bin/env python3
# Copyright 2026 ReturnGift Project. All rights reserved.
# Licensed under the Apache License, Version 2.0.
#
# Kotlin/Java STRUCTURAL verifier for CI pre-flight.
#
# Why this exists
# ---------------
# scripts/ci-preflight.sh only has grep guards. Grep cannot see brace structure, so
# three separate incidents shipped a tree that could not compile:
#
#   1. an extra `}` in DefaultAgentService.runAgentLoop closed the tool-dispatch `for`
#      loop early -> ~40 "unresolved reference toolName/params/taskId" errors reported
#      200 lines away from the real defect.
#   2. an extra `}` in ChatScreen closed the @Composable early -> the dialogs and top
#      bar fell to top level -> ~50 phantom "unresolved Column/Box/Row/Spacer" errors.
#   3. an extra `}` in LearnedProcedureStore closed `object` early.
#
# In every case the compiler reported ~100 errors and the actual defect was ONE brace.
# A naive brace counter cannot do this job either — it false-positives on
# Regex("""...""") raw strings that END in a quote, on backtick test-function names
# containing an apostrophe, and on '{' char literals. This is a real lexer.
#
# Kotlin-vs-Java differences that matter here and are handled explicitly:
#   * Kotlin block comments NEST ("/*" inside a comment opens a new level); Java's
#     do not. Getting this wrong either hides or invents defects, so it is per-language.
#   * A Kotlin raw string is terminated by the LAST of a run of >=3 quotes, so
#     `Regex("""..."([^"]*?)"""")` is valid and must not read as unterminated.
#   * Kotlin allows `fun \`name with spaces and don't\`()`; the apostrophe there is
#     NOT a char literal.
#
# Usage:
#   scripts/kotlin-structure-check.py                 # check app/src, exit 1 on defect
#   scripts/kotlin-structure-check.py --map FILE      # print declaration map for FILE
import os
import re
import sys

RESET, RED, GRN, YEL = "\033[0m", "\033[31m", "\033[32m", "\033[33m"


def scan(src, nest_comments=True, line_ctx=None):
    """Lex `src`; return (brace_events, unterminated).

    brace_events: [('{'|'}', line, col)] for braces in CODE context only.
    unterminated: None, or (kind, line) describing the unclosed construct.
    line_ctx: optional dict, filled with {line_number: context_kind_at_line_start}.
        Needed to tell real code from the many lines of prompt text living inside
        \"\"\"raw strings\"\"\" — indentation-based diagnostics are meaningless there.
    """
    events = []
    i, n = 0, len(src)
    line, col = 1, 1
    ctx = [["code", 0]]
    started = [("code", 1)]
    if line_ctx is not None:
        line_ctx[1] = "code"

    def adv(k=1):
        nonlocal i, line, col
        for _ in range(k):
            if i < n and src[i] == "\n":
                line += 1
                col = 1
                if line_ctx is not None:
                    line_ctx[line] = ctx[-1][0]
            else:
                col += 1
            i += 1

    while i < n:
        kind = ctx[-1][0]

        if kind == "code":
            c = src[i]

            if src.startswith("//", i):
                j = src.find("\n", i)
                adv((n - i) if j < 0 else (j - i))
                continue

            if src.startswith("/*", i):
                start_line = line
                if nest_comments:
                    depth = 0
                    while i < n:
                        if src.startswith("/*", i):
                            depth += 1
                            adv(2)
                        elif src.startswith("*/", i):
                            depth -= 1
                            adv(2)
                            if depth == 0:
                                break
                        else:
                            adv()
                    if depth != 0:
                        return events, ("nested block comment", start_line)
                else:
                    adv(2)
                    j = src.find("*/", i)
                    if j < 0:
                        return events, ("block comment", start_line)
                    adv(j + 2 - i)
                continue

            # backtick-quoted identifier: `fun \`don't do this\`()`
            if c == "`":
                start_line = line
                adv()
                while i < n and src[i] != "`":
                    if src[i] == "\n":
                        return events, ("backtick identifier", start_line)
                    adv()
                if i >= n:
                    return events, ("backtick identifier", start_line)
                adv()
                continue

            if src.startswith('"""', i):
                ctx.append(["raw", 0])
                started.append(("raw string", line))
                adv(3)
                continue

            if c == '"':
                ctx.append(["str", 0])
                started.append(("string", line))
                adv()
                continue

            if c == "'":
                start_line = line
                adv()
                closed = False
                while i < n:
                    if src[i] == "\\":
                        adv(2)
                        continue
                    if src[i] == "'":
                        adv()
                        closed = True
                        break
                    if src[i] == "\n":
                        break
                    adv()
                if not closed:
                    return events, ("char literal", start_line)
                continue

            if c == "{":
                events.append(("{", line, col))
                ctx[-1][1] += 1
                adv()
                continue

            if c == "}":
                # a `}` at template depth 0 closes a ${...} expression
                if len(ctx) > 1 and ctx[-1][1] == 0:
                    ctx.pop()
                    started.pop()
                    adv()
                    continue
                events.append(("}", line, col))
                ctx[-1][1] -= 1
                adv()
                continue

            adv()
            continue

        if kind == "str":
            if src[i] == "\\":
                adv(2)
                continue
            if src.startswith("${", i):
                ctx.append(["code", 0])
                started.append(("template", line))
                adv(2)
                continue
            if src[i] == '"':
                ctx.pop()
                started.pop()
                adv()
                continue
            if src[i] == "\n":
                return events, ("string", line)
            adv()
            continue

        # raw string
        if src.startswith("${", i):
            ctx.append(["code", 0])
            started.append(("template", line))
            adv(2)
            continue
        if src[i] == '"':
            run = 0
            while i + run < n and src[i + run] == '"':
                run += 1
            if run >= 3:
                # the LAST three quotes terminate; the rest are content
                adv(run)
                ctx.pop()
                started.pop()
            else:
                adv(run)
            continue
        adv()
        continue

    if len(ctx) > 1:
        k, l = started[-1]
        return events, (k, l)
    return events, None


def balance(events):
    stack, extra = [], []
    for tok, line, _col in events:
        if tok == "{":
            stack.append(line)
        elif stack:
            stack.pop()
        else:
            extra.append(line)
    return stack, extra


def blocks_of(events):
    """[(open_line, close_line, depth)] for every balanced brace block."""
    stack, out = [], []
    for tok, line, _col in events:
        if tok == "{":
            stack.append(line)
        elif stack:
            out.append((stack.pop(), line, len(stack)))
    return out


def scope_at_line(events, total_lines):
    """scope[L] = open-line of the innermost block enclosing the START of line L."""
    by_line = {}
    for tok, line, _col in events:
        by_line.setdefault(line, []).append(tok)
    scope, stack = {}, []
    for L in range(1, total_lines + 2):
        scope[L] = stack[-1] if stack else 0
        for tok in by_line.get(L, ()):
            if tok == "{":
                stack.append(L)
            elif stack:
                stack.pop()
    return scope


DECL = re.compile(
    r"^(?:@\w+(?:\([^)]*\))?\s*)*"
    r"(?:public |internal |private |protected |abstract |open |sealed |data |value "
    r"|inline |annotation |final |static )*"
    r"(object|class|interface|enum class|fun|val|var)\s+([A-Za-z_]\w*)"
)


def declaration_map(src, events):
    """Column-0 declarations and the line their block closes on.

    This is what makes "is this `fun` inside the object or not?" answerable. A file
    whose members belong to one `object` must show exactly ONE top-level declaration.
    """
    lines = src.split("\n")
    tops = [(s, e) for (s, e, d) in blocks_of(events) if d == 0]
    out = []
    for idx, raw in enumerate(lines, start=1):
        if not raw or raw[0].isspace():
            continue
        m = DECL.match(raw)
        if not m:
            continue
        end = next((e for (s, e) in tops if idx <= s <= idx + 3), None)
        out.append((idx, m.group(1), m.group(2), end))
    return out


FUN = re.compile(
    r"^(\s*)(?:(?:public|internal|private|protected|override|open|suspend|inline"
    r"|operator|external|final|static|abstract|tailrec|infix)\s+)*"
    r"fun\s+(?:<[^>]*>\s+)?(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*|`[^`]+`)\s*\("
)


def _param_types(src, open_paren):
    """Normalized parameter type list starting at the '(' index, or None."""
    depth, i, n = 0, open_paren, len(src)
    buf = []
    while i < n:
        c = src[i]
        if c in "\"'":
            q = c
            i += 1
            while i < n and src[i] != q:
                i += 2 if src[i] == "\\" else 1
            i += 1
            buf.append("§")
            continue
        if c in "([<":
            depth += 1
        elif c in ")]>":
            depth -= 1
            if depth == 0 and c == ")":
                break
        buf.append(c)
        i += 1
    inner = "".join(buf)[1:]
    params, depth, cur = [], 0, ""
    for c in inner:
        if c in "([<{":
            depth += 1
        elif c in ")]>}":
            depth -= 1
        if c == "," and depth == 0:
            params.append(cur)
            cur = ""
        else:
            cur += c
    if cur.strip():
        params.append(cur)
    types = []
    for p in params:
        p = p.split("=")[0]
        if ":" not in p:
            types.append("?")
            continue
        head, tail = p.split(":", 1)
        # `vararg x: String` erases to String[] on the JVM, so it does NOT collide
        # with `x: String` — keep the modifier in the key.
        prefix = "vararg " if re.search(r"\bvararg\b", head) else ""
        types.append(prefix + re.sub(r"\s+", "", tail))
    return ",".join(types)


def duplicate_signatures(src, events):
    """Same enclosing scope + same name + same parameter types = conflicting overload.

    Keyed on the enclosing brace block so an interface declaration and its
    implementing object, or two `override fun onError` in different anonymous objects,
    are correctly treated as distinct. This is the ExecutionTracker
    observationCountsByPackage defect class, invisible to grep.
    """
    lines = src.split("\n")
    scope = scope_at_line(events, len(lines))
    offsets, pos = [], 0
    for raw in lines:
        offsets.append(pos)
        pos += len(raw) + 1
    seen, dupes = {}, []
    for idx, raw in enumerate(lines, start=1):
        m = FUN.match(raw)
        if not m:
            continue
        types = _param_types(src, offsets[idx - 1] + m.end() - 1)
        if types is None:
            continue
        key = (scope[idx], m.group(2), types)
        if key in seen:
            dupes.append((seen[key], idx, m.group(2), types))
        else:
            seen[key] = idx
    return dupes


def check_file(path):
    with open(path, encoding="utf-8", errors="replace") as fh:
        src = fh.read()
    problems = []
    is_kt = path.endswith(".kt")
    events, unterminated = scan(src, nest_comments=is_kt)
    if unterminated:
        kind, line = unterminated
        extra = ""
        if kind == "nested block comment":
            extra = (" — Kotlin block comments NEST, so a '/*' sequence inside the "
                     "comment body (e.g. a path like /api/*) opens another level")
        problems.append("unterminated %s opened at line %d%s" % (kind, line, extra))
        return problems, src, events
    unclosed, extra = balance(events)
    if extra:
        problems.append(
            "EXTRA '}' at line(s) %s — closes an enclosing block early; expect a large "
            "cascade of unrelated 'unresolved reference' errors far from this line"
            % ", ".join(map(str, extra[:5]))
        )
    if unclosed:
        problems.append("UNCLOSED '{' opened at line(s) %s"
                        % ", ".join(map(str, unclosed[:5])))
    if is_kt and not extra and not unclosed:
        for a, b, name, types in duplicate_signatures(src, events):
            problems.append(
                "conflicting overloads: fun %s(%s) declared at lines %d and %d "
                "in the same scope" % (name, types, a, b))
    return problems, src, events


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    os.chdir(root)

    if "--map" in sys.argv:
        target = sys.argv[sys.argv.index("--map") + 1]
        problems, src, events = check_file(target)
        for p in problems:
            print(RED + "  " + p + RESET)
        print("Top-level declarations in %s (%d lines):"
              % (target, src.count("\n") + 1))
        for line, kw, name, end in declaration_map(src, events):
            print("  L%-5d %-12s %-34s %s"
                  % (line, kw, name, ("closes at %d" % end) if end else "no block"))
        return 1 if problems else 0

    if "--depth" in sys.argv:
        # Pinpoint WHICH brace is wrong, not merely that the file is unbalanced.
        # Compares lexer brace depth against 4-space indentation on real code lines
        # only (prompt text inside raw strings is excluded). The first divergence is
        # the defect; everything after it is cascade.
        target = sys.argv[sys.argv.index("--depth") + 1]
        with open(target, encoding="utf-8", errors="replace") as fh:
            src = fh.read()
        lctx = {}
        events, _unt = scan(src, nest_comments=target.endswith(".kt"), line_ctx=lctx)
        by = {}
        for tok, line, col in events:
            by.setdefault(line, []).append((col, tok))
        lines = src.split("\n")
        depth, shown = 0, 0
        print("Divergences between brace depth and indentation in %s:" % target)
        for idx, raw in enumerate(lines, start=1):
            at_start = depth
            for _c, tok in sorted(by.get(idx, [])):
                depth += 1 if tok == "{" else -1
            s = raw.strip()
            if lctx.get(idx, "code") != "code":
                continue
            if not s or s.startswith("//") or s.startswith("*") or s.startswith("/*"):
                continue
            indent = len(raw) - len(raw.lstrip())
            expect = indent // 4 + (1 if s.startswith("}") else 0)
            if expect != at_start:
                print("  L%-5d lexer_depth=%-3d indent_implies=%-3d | %s"
                      % (idx, at_start, expect, raw[:100]))
                shown += 1
                if shown >= 15:
                    print("  ... (stopping; fix the FIRST divergence, the rest is cascade)")
                    break
        if shown == 0:
            print("  none — indentation and brace depth agree throughout")
        return 0

    targets = []
    for base in ("app/src/main", "app/src/test", "app/src/androidTest"):
        for dirpath, _dirs, files in os.walk(base):
            for f in files:
                if f.endswith((".kt", ".java")):
                    targets.append(os.path.join(dirpath, f))

    failed = 0
    for path in sorted(targets):
        problems, _src, _ev = check_file(path)
        if problems:
            failed += 1
            print(RED + "STRUCTURE FAIL " + path + RESET)
            for p in problems:
                print("    " + p)

    if failed:
        print("")
        print(RED + "%d file(s) are structurally broken and cannot compile." % failed
              + RESET)
        print(YEL + "Tip: scripts/kotlin-structure-check.py --map <file> shows which "
              "block each brace actually closes." + RESET)
        return 1
    print(GRN + "OK kotlin-structure (%d files lexed; braces balanced; no conflicting "
          "overloads)" % len(targets) + RESET)
    return 0


if __name__ == "__main__":
    sys.exit(main())
