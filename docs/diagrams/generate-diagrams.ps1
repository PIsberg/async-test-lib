# Generate PlantUML Diagrams for Async Test Library
# This script downloads PlantUML if needed and generates all diagrams

$ErrorActionPreference = "Stop"
$plantumlVersion = "1.2024.7"
$plantumlJar = "plantuml-$plantumlVersion.jar"
$downloadUrl = "https://github.com/plantuml/plantuml/releases/download/v$plantumlVersion/plantuml-$plantumlVersion.jar"
$diagramsDir = $PSScriptRoot

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  PlantUML Diagram Generator" -ForegroundColor Cyan
Write-Host "  Async Test Library" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Change to diagrams directory
Set-Location $diagramsDir

# Check if PlantUML JAR exists
if (-not (Test-Path $plantumlJar)) {
    Write-Host "Downloading PlantUML $plantumlVersion..." -ForegroundColor Yellow
    
    try {
        Invoke-WebRequest -Uri $downloadUrl -OutFile $plantumlJar -UseBasicParsing
        Write-Host "✓ Downloaded PlantUML JAR" -ForegroundColor Green
    } catch {
        Write-Host "✗ Failed to download PlantUML" -ForegroundColor Red
        Write-Host "Error: $($_.Exception.Message)" -ForegroundColor Red
        Write-Host ""
        Write-Host "Please download manually from:" -ForegroundColor Yellow
        Write-Host "  $downloadUrl" -ForegroundColor Cyan
        exit 1
    }
} else {
    Write-Host "✓ PlantUML JAR found: $plantumlJar" -ForegroundColor Green
}

# Check if Java is available
Write-Host "Checking Java installation..." -ForegroundColor Yellow
try {
    $javaVersion = java -version 2>&1 | Select-String "version" | Select-Object -First 1
    Write-Host "✓ Java found: $javaVersion" -ForegroundColor Green
} catch {
    Write-Host "✗ Java not found in PATH" -ForegroundColor Red
    Write-Host "Please install Java 21+ and add to PATH" -ForegroundColor Yellow
    exit 1
}

# Count PUML files
$pumlFiles = Get-ChildItem -Filter "*.puml" | Where-Object { $_.Name -ne "README.md" }
Write-Host ""
Write-Host "Found $($pumlFiles.Count) PlantUML source files:" -ForegroundColor Cyan
$pumlFiles | ForEach-Object { Write-Host "  - $($_.Name)" }
Write-Host ""

# Generate diagrams
Write-Host "Generating PNG diagrams..." -ForegroundColor Yellow
Write-Host ""

$successCount = 0
$failCount = 0

foreach ($pumlFile in $pumlFiles) {
    $pngFile = $pumlFile.BaseName + ".png"
    Write-Host "  Generating: $pngFile" -NoNewline
    
    try {
        $output = java -jar $plantumlJar -tpng $pumlFile.Name 2>&1
        if ($LASTEXITCODE -eq 0) {
            Write-Host " ✓" -ForegroundColor Green
            $successCount++
        } else {
            Write-Host " ✗" -ForegroundColor Red
            Write-Host "    Error: $output" -ForegroundColor Red
            $failCount++
        }
    } catch {
        Write-Host " ✗" -ForegroundColor Red
        Write-Host "    Error: $($_.Exception.Message)" -ForegroundColor Red
        $failCount++
    }
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Generation Complete!" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Success: $successCount diagrams" -ForegroundColor Green
Write-Host "  Failed:  $failCount diagrams" -ForegroundColor $(if ($failCount -eq 0) { "Green" } else { "Red" })
Write-Host ""

# List generated files
$pngFiles = Get-ChildItem -Filter "*.png"
if ($pngFiles.Count -gt 0) {
    Write-Host "Generated PNG files:" -ForegroundColor Cyan
    $pngFiles | ForEach-Object {
        $size = "{0:N0}" -f $_.Length
        Write-Host "  - $($_.Name) ($size bytes)"
    }
    Write-Host ""
}

# Open folder if running interactively
if ($Host.Name -eq "ConsoleHost") {
    Write-Host "Opening diagrams folder..." -ForegroundColor Yellow
    explorer.exe $diagramsDir
}

Write-Host "Done!" -ForegroundColor Green
