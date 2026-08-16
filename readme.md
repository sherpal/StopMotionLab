# Stop Motion Lab


### Certificates shenanigans

```
openssl pkcs12 -export `
  -out certs/server.p12 `
  -inkey certs/192.168.0.12+2-key.pem `
  -in certs/192.168.0.12+2.pem `
  -name stop-motion-lab
```

mdp: stopmotion
