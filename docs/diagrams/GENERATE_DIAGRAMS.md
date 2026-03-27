# Generate PlantUML Diagrams

This script generates PNG images from PlantUML source files.

## Prerequisites

### Option 1: PlantUML CLI (Recommended)

1. Install Java 21+ (already required for this project)
2. Download PlantUML JAR:
   ```bash
   curl -L https://github.com/plantuml/plantuml/releases/download/v1.2024.7/plantuml-1.2024.7.jar -o plantuml.jar
   ```

3. Generate all diagrams:
   ```bash
   java -jar plantuml.jar -tpng *.puml
   ```

### Option 2: PlantUML Server

Run a local PlantUML server:
```bash
docker run -d -p 8080:8080 plantuml/plantuml-server:jetty
```

Then visit http://localhost:8080 to render diagrams online.

### Option 3: IDE Plugin

Install PlantUML plugin for your IDE:
- **IntelliJ IDEA**: PlantUML Integration plugin
- **VS Code**: PlantUML extension
- **Eclipse**: PlantUML plugin

## Generate All Diagrams

```bash
cd docs/diagrams

# Using PlantUML JAR
java -jar plantuml.jar -tpng *.puml

# This will generate:
# - system-context.png
# - container.png
# - component-flow.png
# - sequence-execution.png
# - class-diagram.png
# - benchmark-sequence.png
# - activity-diagram.png
# - deployment-diagram.png
# - detector-architecture.png
```

## Batch Script for Windows

Create `generate-diagrams.bat`:

```batch
@echo off
echo Generating PlantUML diagrams...
cd /d "%~dp0"
java -jar plantuml.jar -tpng *.puml
echo Done! Generated %CD%\*.png
pause
```

## PowerShell Script

Create `generate-diagrams.ps1`:

```powershell
$plantumlJar = "plantuml.jar"
$downloadUrl = "https://github.com/plantuml/plantuml/releases/download/v1.2024.7/plantuml-1.2024.7.jar"

if (-not (Test-Path $plantumlJar)) {
    Write-Host "Downloading PlantUML..."
    Invoke-WebRequest -Uri $downloadUrl -OutFile $plantumlJar
}

Write-Host "Generating diagrams..."
java -jar $plantumlJar -tpng *.puml

Write-Host "Done! Generated diagrams:"
Get-ChildItem *.png | ForEach-Object { Write-Host "  - $($_.Name)" }
```

## Verify Generation

After running the script, you should have these PNG files:

- `system-context.png`
- `container.png`
- `component-flow.png`
- `sequence-execution.png`
- `class-diagram.png`
- `benchmark-sequence.png`
- `activity-diagram.png`
- `deployment-diagram.png`
- `detector-architecture.png`

## Troubleshooting

### "Java not found"
Ensure Java 21+ is installed and in your PATH:
```bash
java -version
```

### "Out of memory"
Increase Java heap size:
```bash
java -Xmx512m -jar plantuml.jar -tpng *.puml
```

### Diagrams look cut off
Try increasing the size:
```bash
java -jar plantuml.jar -tpng -scale 1.5 *.puml
```

## Alternative: Use Online Renderer

If you don't want to install PlantUML locally:

1. Visit https://www.plantuml.com/plantuml/
2. Copy the content of any `.puml` file
3. Paste and render online
4. Download as PNG

## CI/CD Integration

Add to your GitHub Actions workflow:

```yaml
- name: Generate PlantUML Diagrams
  run: |
    curl -L https://github.com/plantuml/plantuml/releases/download/v1.2024.7/plantuml-1.2024.7.jar -o plantuml.jar
    java -jar plantuml.jar -tpng docs/diagrams/*.puml
    mv *.png docs/diagrams/
```
