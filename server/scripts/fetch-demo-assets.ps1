param([switch]$VerifyOnly)
$ErrorActionPreference = 'Stop'
$serverRoot = Split-Path $PSScriptRoot -Parent
$assetRoot = Join-Path $serverRoot 'src/main/resources/static/demo-assets'
$downloadRoot = Join-Path $serverRoot 'target/asset-downloads'
$catalog = Get-Content -LiteralPath (Join-Path $assetRoot 'catalog.json') -Raw | ConvertFrom-Json
if (-not $VerifyOnly) { New-Item -ItemType Directory -Path $downloadRoot -Force | Out-Null }
foreach ($asset in $catalog.assets) {
    if ($asset.file -notmatch '^[a-z0-9-]+\.(jpg|png|mp4)$') { throw 'Invalid asset filename' }
    $destination = Join-Path $assetRoot $asset.file
    if (-not (Test-Path -LiteralPath $destination)) {
        if ($VerifyOnly) { throw "Missing asset: $($asset.file)" }
        $download = Join-Path $downloadRoot ($asset.file + '.source')
        Invoke-WebRequest -Uri $asset.downloadUrl -OutFile $download -UserAgent 'PixelFlow-Course-Demo/0.1' -TimeoutSec 45
        if ($asset.kind -eq 'video') {
            & ffmpeg -hide_banner -loglevel error -n -i $download -vf 'scale=-2:720' -c:v libx264 -crf 28 -preset medium -pix_fmt yuv420p -c:a aac -b:a 96k -movflags +faststart $destination
            if ($LASTEXITCODE -ne 0) { throw "Video conversion failed: $($asset.file)" }
        } else {
            Copy-Item -LiteralPath $download -Destination $destination
        }
    }
    $hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($asset.PSObject.Properties.Name -contains 'sha256' -and $hash -ne $asset.sha256) { throw "Hash mismatch: $($asset.file); review source changes before replacing assets" }
    [pscustomobject]@{File=$asset.file;Bytes=(Get-Item -LiteralPath $destination).Length;SHA256=$hash}
}
