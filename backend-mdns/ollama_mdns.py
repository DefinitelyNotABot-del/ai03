from zeroconf import Zeroconf, ServiceInfo
import socket

def get_local_ip():
    # This gets your primary LAN IP (works for WiFi/hotspot)
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        # Doesn't need to be reachable; just used to get the right interface
        s.connect(('8.8.8.8', 80))
        ip = s.getsockname()[0]
    except Exception:
        ip = '127.0.0.1'
    finally:
        s.close()
    return ip

local_ip = get_local_ip()
ip_bytes = bytes(map(int, local_ip.split('.')))

info = ServiceInfo(
    "_ollama._tcp.local.",
    "OllamaServer._ollama._tcp.local.",
    addresses=[ip_bytes],  # <-- Now dynamic!
    port=11434,
    properties={},
    server="ollama.local.",
)

zeroconf = Zeroconf()
print(f"Advertising Ollama server on LAN at {local_ip}:11434 ...")
zeroconf.register_service(info)
input("Press enter to exit...\n")
zeroconf.unregister_service(info)
zeroconf.close()
