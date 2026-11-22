# Baixa o modelo recomendado Ternary Bonsai 2 2B (Q2_0, compativel com llama.cpp upstream)
$ErrorActionPreference = "Stop"
$dir = Join-Path $PSScriptRoot "..\models"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$url = "https://huggingface.co/prism-ml/Ternary-Bonsai-2-2B-gguf/resolve/main/Ternary-Bonsai-2-2B-Q2_0.gguf"
$out = Join-Path $dir "Ternary-Bonsai-2-2B-Q2_0.gguf"
Write-Host "Baixando $url -> $out"
Invoke-WebRequest -Uri $url -OutFile $out
Write-Host "Concluido. Copie o arquivo para o device e importe no app."