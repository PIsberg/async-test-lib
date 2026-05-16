Output docs/diagrams/demo.gif

Set Theme "Catppuccin Mocha"
Set FontSize 14
Set Width 100
Set Height 38
Set Padding 20
Set TypingSpeed 45ms
Set CursorBlink false

# Intercept "mvn test" so the tape doesn't need a real Maven build
Hide
Type "function mvn { bash docs/demo/run.sh; }"
Enter
Type "clear"
Enter
Show

# Show the test file
Sleep 500ms
Type "cat docs/demo/CounterTest.java"
Enter
Sleep 3500ms

# Run the "test"
Sleep 500ms
Type "mvn test -Dtest=CounterTest -q"
Enter
Sleep 8500ms

Sleep 2s
