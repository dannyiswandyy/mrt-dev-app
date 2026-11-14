"""Checks the rules in AGENTS.md, so a push that breaks one goes red.

Run from the repository root:  python3 tests/rules_check.py
Needs Python 3 with PyYAML, and Node (for the script syntax checks).
"""
import os, re, subprocess, sys, tempfile
import yaml

PAGE = "android/app/src/main/assets/index.html"
INTEL = "android/app/src/main/assets/mrt-intelligence.js"
BUILD = ".github/workflows/build-android-dev.yml"
MAX_WORKFLOW = 480 * 1024
problems = []

def bad(msg):
    problems.append(msg)
    print("FAIL:", msg)

# 1. Workflow files: valid YAML, under 480 KB, and every Python script in them compiles.
heredoc = re.compile(r"python3?\s+-\s*<<-?\s*['\"]?(\w+)['\"]?[^\n]*\n(.*?)\n\s*\1\s*$", re.S | re.M)
for name in sorted(os.listdir(".github/workflows")):
    path = os.path.join(".github/workflows", name)
    if not name.endswith((".yml", ".yaml")):
        bad(f"{path}: only workflow files (.yml) belong in .github/workflows")
        continue
    size = os.path.getsize(path)
    if size > MAX_WORKFLOW:
        bad(f"{path} is {size // 1024} KB; keep workflow files under 480 KB (GitHub won't run them at 500 KB)")
    try:
        wf = yaml.safe_load(open(path, encoding="utf-8"))
    except yaml.YAMLError as e:
        bad(f"{path} isn't valid YAML: {e}")
        continue
    for job_name, job in (wf.get("jobs") or {}).items():
        for i, step in enumerate(job.get("steps") or []):
            run = step.get("run") or ""
            label = f"{path}, step \"{step.get('name') or i + 1}\""
            for m in heredoc.finditer(run):
                try:
                    compile(m.group(2), label, "exec")
                except SyntaxError as e:
                    bad(f"{label}: its Python script doesn't run (line {e.lineno}: {e.msg})")
            if path == BUILD and re.search(r"index\.html|</body>|mrt-intelligence", run):
                bad(f"{label} touches the app page; edit {PAGE} itself instead of patching it during the build")

# 2. The app page: there, still has the features other AIs must not lose, and its scripts parse.
if not os.path.isfile(PAGE):
    bad(f"{PAGE} is missing")
else:
    html = open(PAGE, encoding="utf-8").read()
    for needle, what in [("function lineCuts(", "disruption turn-back (lineCuts)"),
                         ("function cutTrain(", "disruption turn-back (cutTrain)"),
                         ("CAM_ANGLES", "Live cam angles (CAM_ANGLES)"),
                         ('<script src="mrt-intelligence.js"></script>', "the timetable intelligence script tag"),
                         ("http-equiv=\"Content-Security-Policy\"", "the security rules (CSP)")]:
        if needle not in html:
            bad(f"{PAGE} no longer has {what}")
    scripts = re.findall(r"<script(?![^>]*\bsrc=)[^>]*>(.*?)</script>", html, re.S)
    for k, js in enumerate(scripts):
        with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False, encoding="utf-8") as f:
            f.write(js)
        r = subprocess.run(["node", "--check", f.name], capture_output=True, text=True)
        os.unlink(f.name)
        if r.returncode:
            bad(f"{PAGE}: <script> block {k + 1} has a syntax error:\n{r.stderr.strip()[-600:]}")
if not os.path.isfile(INTEL):
    bad(f"{INTEL} is missing")
else:
    r = subprocess.run(["node", "--check", INTEL], capture_output=True, text=True)
    if r.returncode:
        bad(f"{INTEL} has a syntax error:\n{r.stderr.strip()[-600:]}")

# 3. No packed copies of the page or keys in the repository.
for root, dirs, files in os.walk("."):
    dirs[:] = [d for d in dirs if d not in (".git", "node_modules")]
    for fn in files:
        if fn.endswith(".b64"):
            bad(f"{os.path.join(root, fn)}: keep the page as plain files in android/, not packed")

if problems:
    print(f"\n{len(problems)} rule(s) broken. See AGENTS.md.")
    sys.exit(1)
print("All AGENTS.md rules checks passed.")
