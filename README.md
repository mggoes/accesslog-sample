Purgeable AccessLog for Undertow
---
Module to enable purgeable access log files for spring-boot based applications using Undertow.  
   
Usage
---
```
accesslog:
  purge:
    enabled: true #default false
    execute-on-startup: true
    execution-interval: 10
    execution-interval-unit: SECONDS
    max-history: 1
    max-history-unit: MINUTES
```
