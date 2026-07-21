package org.claudeproxy.api

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Public installer scripts behind the one-liners shown on the Tokens page:
 *   curl -fsSL <origin>/api/install.sh | bash -s -- <cxp_token>
 *   & ([scriptblock]::Create((irm <origin>/api/install.ps1))) -Token <cxp_token>
 * Unauthenticated on purpose — the scripts carry no secrets; the token arrives as an
 * argument. The gateway base is baked from PUBLIC_BASE_URL (request origin as fallback)
 * and can still be overridden by the script's second argument.
 */
fun Route.installRoutes(publicBaseUrl: String) {
    get("/install.sh") { call.respondText(bashInstaller(gatewayBase(call, publicBaseUrl)), ContentType.Text.Plain) }
    get("/install.ps1") { call.respondText(psInstaller(gatewayBase(call, publicBaseUrl)), ContentType.Text.Plain) }
}

private fun gatewayBase(call: ApplicationCall, publicBaseUrl: String): String {
    val root = publicBaseUrl.ifEmpty {
        val o = call.request.origin
        val port = if (o.serverPort == 80 || o.serverPort == 443) "" else ":${o.serverPort}"
        "${o.scheme}://${o.serverHost}$port"
    }
    return "$root/gateway"
}

private fun bashInstaller(base: String): String = """
#!/usr/bin/env bash
# claude-proxy installer. Usage: curl -fsSL <origin>/api/install.sh | bash -s -- <cxp_token> [base_url]
set -eu
TOKEN="${'$'}{1:-}"
BASE="${'$'}{2:-$base}"
if [ -z "${'$'}TOKEN" ]; then
  echo "Usage: curl -fsSL .../api/install.sh | bash -s -- <cxp_token>" >&2
  exit 1
fi
mkdir -p "${'$'}HOME/.local/bin"
cat > "${'$'}HOME/.local/bin/claude-proxy" <<EOF
#!/usr/bin/env bash
ANTHROPIC_BASE_URL="${'$'}BASE" ANTHROPIC_AUTH_TOKEN="${'$'}TOKEN" exec claude "\${'$'}@"
EOF
chmod +x "${'$'}HOME/.local/bin/claude-proxy"
# macOS (and some distros) don't ship ~/.local/bin on PATH; a piped script can't change the
# calling shell's PATH, so persist it in the shell rc and tell the user to reload.
case ":${'$'}PATH:" in
  *":${'$'}HOME/.local/bin:"*) ;;
  *)
    rc="${'$'}HOME/.${'$'}(basename "${'$'}{SHELL:-zsh}")rc"
    if ! grep -qs '\.local/bin' "${'$'}rc"; then
      echo 'export PATH="${'$'}HOME/.local/bin:${'$'}PATH"' >> "${'$'}rc"
      echo "Added ~/.local/bin to PATH in ${'$'}rc"
    fi
    echo "Open a new terminal (or run: source ${'$'}rc) to pick it up."
    ;;
esac
echo "Installed. Run: claude-proxy [claude args]"
""".trimStart()

private fun psInstaller(base: String): String = """
param(
  [Parameter(Mandatory = ${'$'}true)][string]${'$'}Token,
  [string]${'$'}Base = "$base"
)
# claude-proxy installer: adds a claude-proxy function to the PowerShell profile (idempotent).
# global: makes the function stick in the current session even though this runs in a scriptblock.
if (!(Test-Path ${'$'}PROFILE)) { New-Item -ItemType File -Path ${'$'}PROFILE -Force | Out-Null }
${'$'}fn = "function global:claude-proxy { `${'$'}env:ANTHROPIC_BASE_URL=`"${'$'}Base`"; `${'$'}env:ANTHROPIC_AUTH_TOKEN=`"${'$'}Token`"; claude @args }"
${'$'}lines = @(Get-Content ${'$'}PROFILE | Where-Object { ${'$'}_ -notmatch '^function (global:)?claude-proxy ' })
${'$'}lines + ${'$'}fn | Set-Content ${'$'}PROFILE
. ${'$'}PROFILE
Write-Host 'Installed. Run: claude-proxy [claude args]'
""".trimStart()
