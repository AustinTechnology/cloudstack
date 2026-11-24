"""Parses every PowerShell script the extension sends, with the prelude, using pwsh's parser (syntax only)."""
import base64
import json
import subprocess
import sys

sys.path.insert(0, ".")
import hyperv_cluster as hv  # noqa: E402

bad = 0
for name, body in sorted(hv.SCRIPTS.items()):
    text = hv.PS_PRELUDE + "\n$A = $null\ntry { $r = & { %s } } catch { }\n" % body
    enc = base64.b64encode(text.encode()).decode()
    ps = ("$t=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('%s'));$e=$null;"
          "[void][System.Management.Automation.Language.Parser]::ParseInput($t,[ref]$null,[ref]$e);"
          "$e | ForEach-Object { $_.Extent.StartLineNumber.ToString() + ': ' + $_.Message }") % enc
    out = subprocess.run(["pwsh", "-NoProfile", "-Command", ps], capture_output=True, text=True).stdout.strip()
    print(name, "OK" if not out else "ERRORS\n" + out)
    bad += bool(out)
loader = subprocess.run(["pwsh", "-NoProfile", "-Command",
                         "[void][System.Management.Automation.Language.Parser]::ParseInput(%s,[ref]$null,[ref]$e); $e" % json.dumps(hv.WinRMTransport.LOADER).replace('"', "'")],
                        capture_output=True, text=True).stdout.strip()
print("loader", "OK" if not loader else loader)
sys.exit(1 if bad or loader else 0)
