# Echo Controller Server

This is a simple server that connects the [Echo app](https://github.com/brahmkshatriya/echo) (through the [Remote Control Extension](https://github.com/rebelonion/echo-remote-control)) to the [Echo Controller](https://github.com/rebelonion/echo_controller)

default server is at `wss://ws.rebelonion.dev/ws`

## Installation via Docker
port forward 443 to 443
```bash
docker run -d --name echo-controller-server -p 443:443 echo-controller-server
```

## Installation via Docker Compose
as simple as
```bash
docker-compose up --build -d
```