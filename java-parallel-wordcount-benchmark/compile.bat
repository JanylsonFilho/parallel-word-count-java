@echo off
if not exist bin mkdir bin
javac -cp lib\jocl-2.0.4.jar -d bin src\*.java
