#!/usr/bin/env pwsh
# Ejecutar desde F:\Proys\LogisticsMonitorKotlin\
# PowerShell: .\push_to_github.ps1

Set-Location "F:\Proys\LogisticsMonitorKotlin"

git init
git add .
git commit -m "feat: initial Kotlin migration - AccessibilityService + 2-overlay system + CI/CD"
git branch -M main
git remote add origin https://github.com/fmlucero/CogniPilot.git
git push -u origin main --force
