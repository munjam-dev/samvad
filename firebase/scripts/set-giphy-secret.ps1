# Saves the Giphy API key as the GIPHY_API_KEY Functions secret, then redeploys the two GIF functions.
# Run it yourself, in the firebase folder:
#   powershell -ExecutionPolicy Bypass -File .\scripts\set-giphy-secret.ps1
# It contains no key. You paste the key at a hidden prompt; a temporary file holds it for a moment and is deleted.
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
npx firebase use samvad-messenger

$secure = Read-Host "Paste GIPHY_API_KEY (input is hidden), then press Enter" -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try { $plain = [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr).Trim() }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
if ([string]::IsNullOrWhiteSpace($plain)) { throw "The key was empty." }
$tmp = New-TemporaryFile
try {
  Set-Content -Path $tmp -Value $plain -NoNewline -Encoding ascii
  npx firebase functions:secrets:set GIPHY_API_KEY --data-file $tmp --force
} finally {
  Remove-Item $tmp -Force -ErrorAction SilentlyContinue
  $plain = $null
}
npx firebase deploy --only functions:searchGifs,functions:trendingGifs
Write-Host ""
Write-Host "Done. Tell Claude: giphy set."
