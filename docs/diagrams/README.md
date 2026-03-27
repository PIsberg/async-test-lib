# Async Test Library - Architecture Diagrams

This folder contains PlantUML source files and generated PNG diagrams for the async-test library architecture.

## Generated Diagrams

| Diagram | Source File | PNG File | Description |
|---------|-------------|----------|-------------|
| System Context | `system-context.puml` | `SystemContext.png` (77 KB) | High-level system architecture and external dependencies |
| Container | `container.puml` | `ContainerDiagram.png` (216 KB) | Main containers/components within the JAR |
| Component Flow | `component-flow.puml` | `ComponentFlow.png` (127 KB) | JUnit 5 extension interception and execution flow |
| Sequence Execution | `sequence-execution.puml` | `SequenceExecution.png` (190 KB) | N×M execution pattern with thread synchronization |
| Class Diagram | `class-diagram.puml` | `ClassDiagram.png` (341 KB) | Main classes and their relationships |
| Benchmark Sequence | `benchmark-sequence.puml` | `BenchmarkSequence.png` (148 KB) | Benchmarking flow for baseline creation and comparison |
| Activity Diagram | `activity-diagram.puml` | `ActivityDiagram.png` (209 KB) | Decision flow during test execution |
| Deployment Diagram | `deployment-diagram.puml` | `DeploymentDiagram.png` (170 KB) | Library deployment and usage |
| Detector Architecture | `detector-architecture.puml` | `DetectorArchitecture.png` (117 KB) | Structure and common pattern of all 35 detectors |

**Total Size**: ~1.6 MB

## How to Regenerate Diagrams

### Quick Regeneration

1. Download PlantUML JAR:
   ```bash
   curl -L https://github.com/plantuml/plantuml/releases/download/v1.2024.7/plantuml-1.2024.7.jar -o plantuml.jar
   ```

2. Generate all diagrams:
   ```bash
   java -jar plantuml.jar -tpng *.puml
   ```

### Using PowerShell Script

```powershell
.\generate-diagrams.ps1
```

The script will automatically download PlantUML if not present.

### Requirements

- Java 21+ (for PlantUML)
- PlantUML JAR (download from GitHub Releases)

## Diagram Descriptions

### 1. System Context Diagram (C4 Level 1)

Shows the async-test library in its runtime environment:
- **JUnit 5 Platform**: Discovers and runs tests
- **Async Test Library**: Core functionality
- **User Test Code**: Tests annotated with `@AsyncTest`
- **Benchmark Storage**: Persistent baseline data

### 2. Container Diagram (C4 Level 2)

Internal structure of the async-test JAR:
- **Extension Layer**: JUnit 5 integration
- **Configuration**: Annotation and config objects
- **Runner Core**: Execution orchestration
- **Detector Modules**: 35 concurrency detectors (Phase 1, 2, 3)
- **Benchmark Module**: Performance tracking
- **Lifecycle Annotations**: Before/After hooks

### 3. Component Flow Diagram

Sequence of component interactions:
1. JUnit discovers `@AsyncTest`
2. Extension provides invocation context
3. Interceptor skips standard execution
4. Runner creates detectors and context
5. N×M execution loop
6. Benchmarking records times
7. Detector analysis and reporting

### 4. Sequence Execution Diagram

Detailed N×M execution pattern:
- Barrier synchronization for M threads
- N invocation rounds
- Per-thread context installation
- Concurrent event recording
- Benchmark comparison
- Detector analysis

### 5. Class Diagram

Complete class structure:
- `AsyncTest` annotation (35+ parameters)
- `AsyncTestConfig` (immutable configuration)
- `AsyncTestExtension` (JUnit 5 integration)
- `ConcurrencyRunner` (execution engine)
- `AsyncTestContext` (ThreadLocal context)
- `DetectorType` (enumeration)
- Benchmark classes (5 classes)
- All detector classes

### 6. Benchmark Sequence Diagram

Benchmarking workflow:
- **First Run**: Baseline creation
- **Subsequent Runs**: Comparison with baseline
- **Regression Detection**: Threshold-based alerts
- **Fail Mode**: Optional test failure on regression

### 7. Activity Diagram

Complete execution flow:
- Extension layer decision tree
- Runner setup
- Execution loop (N invocations × M threads)
- Benchmarking decisions
- Detector analysis
- Error handling

### 8. Deployment Diagram

Physical deployment structure:
- JAR file contents
- Maven repository layout
- User project integration
- Benchmark data storage

### 9. Detector Architecture Diagram

Detector organization:
- **Phase 1**: Core detectors (9 classes)
- **Phase 2**: Advanced detectors (20 classes)
- **Phase 3**: Runtime validators (5 classes)
- Common pattern: Record events → Analyze → Report

## Editing Diagrams

1. Edit the `.puml` source file
2. Regenerate PNG: `java -jar plantuml-1.2024.7.jar -tpng filename.puml`
3. Commit both source and PNG files

## PlantUML Syntax Reference

For PlantUML syntax, see:
- [PlantUML Official Documentation](https://plantuml.com/)
- [C4-PlantUML](https://github.com/plantuml-stdlib/C4-PlantUML) (for C4 diagrams)

## Version History

- **1.1.0** (March 2026): Initial diagrams with benchmarking module
- **Future**: Update diagrams when architecture changes

## Related Documentation

- [ARCHITECTURE.md](ARCHITECTURE.md) - Full architecture documentation
- [BENCHMARKING.md](BENCHMARKING.md) - Benchmarking guide
- [README.md](../../README.md) - Project overview
