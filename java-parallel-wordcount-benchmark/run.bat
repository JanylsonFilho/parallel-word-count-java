@echo off
if not exist bin mkdir bin
if not exist results mkdir results
if not exist images mkdir images
javac -cp lib\jocl-2.0.4.jar -d bin src\*.java
set WORD=%1
if "%WORD%"=="" set WORD=the
java -cp bin;lib\jocl-2.0.4.jar WordCounterBenchmark %WORD%
