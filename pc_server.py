import http.server
import socketserver
import json
import socket
import sys
import os
import time

# Try to import keyboard automation library or fallback to clipboard
try:
    import pyautogui
    PYAUTOGUI_AVAILABLE = True
except ImportError:
    PYAUTOGUI_AVAILABLE = False
    print("Warning: 'pyautogui' not found. Will use clipboard fallback for typing.")
    print("To enable fast direct keyboard typing, run: pip install pyautogui")

class PCServerHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        # Suppress standard request logs for cleaner console output
        pass

    def do_GET(self):
        # Handle auto-discovery requests
        if self.path in ["/", "/discover", "/info"]:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            
            response = {
                "name": f"PC Server ({socket.gethostname()})",
                "hostname": socket.gethostname(),
                "status": "online"
            }
            self.wfile.write(json.dumps(response).encode("utf-8"))
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        if self.path == "/scan":
            content_length = int(self.headers.get('Content-Length', 0))
            post_data = self.rfile.read(content_length)
            
            try:
                payload = json.loads(post_data.decode("utf-8"))
                data = payload.get("data", "")
                raw_data = payload.get("raw_data", "")
                scan_type = payload.get("type", "UNKNOWN")
                chain = payload.get("chain", ["paste", "enter"])
                auto_submit = payload.get("auto_submit", True)
                
                print(f"\n[+] Scanned {scan_type}: {data}")
                
                # Input the data to active cursor
                self.type_data(data, chain, auto_submit)
                
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(json.dumps({"success": True}).encode("utf-8"))
            except Exception as e:
                print(f"[-] Error processing scan: {e}")
                self.send_response(500)
                self.end_headers()
        else:
            self.send_response(404)
            self.end_headers()

    def do_OPTIONS(self):
        # CORS preflight
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def type_data(self, data, chain, auto_submit):
        # If pyautogui is available, type or paste the code
        if PYAUTOGUI_AVAILABLE:
            for action in chain:
                if action == "paste":
                    # Use clipboard copy-paste (safer for special characters)
                    self.set_clipboard(data)
                    time.sleep(0.05)
                    # Paste keyboard shortcut
                    if sys.platform == "darwin":
                        pyautogui.hotkey("command", "v")
                    else:
                        pyautogui.hotkey("ctrl", "v")
                elif action == "enter":
                    pyautogui.press("enter")
                elif action == "tab":
                    pyautogui.press("tab")
                time.sleep(0.05)
        else:
            # Fallback to copy to clipboard
            self.set_clipboard(data)
            print(f"    [!] Copied to clipboard (direct typing disabled).")
            # If clipboard-only, notify user
            if "enter" in chain:
                print("    [!] Hint: Install pyautogui to automatically press Enter.")

    def set_clipboard(self, text):
        if sys.platform == "win32":
            import ctypes
            # Quick direct Windows API clipboard write
            ctypes.windll.user32.OpenClipboard(0)
            ctypes.windll.user32.EmptyClipboard()
            hCd = ctypes.windll.kernel32.GlobalAlloc(2, len(text) + 1)
            pchCd = ctypes.windll.kernel32.GlobalLock(hCd)
            ctypes.cdll.msvcrt.strcpy(ctypes.c_char_p(pchCd), text.encode("utf-8"))
            ctypes.windll.kernel32.GlobalUnlock(hCd)
            ctypes.windll.user32.SetClipboardData(1, hCd) # CF_TEXT
            ctypes.windll.user32.CloseClipboard()
        elif sys.platform == "darwin":
            os.system(f"echo '{text}' | pbcopy")
        else:
            # Linux fallback
            try:
                import subprocess
                process = subprocess.Popen(['xclip', '-selection', 'clipboard'], stdin=subprocess.PIPE)
                process.communicate(input=text.encode('utf-8'))
            except Exception:
                try:
                    import subprocess
                    process = subprocess.Popen(['xsel', '--clipboard', '--input'], stdin=subprocess.PIPE)
                    process.communicate(input=text.encode('utf-8'))
                except Exception:
                    pass

def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"

def print_qr_code(ip, port):
    # Generates a text-based QR code in the terminal for extremely easy connection pairing!
    import urllib.parse
    connection_string = f"http://{ip}:{port}"
    print("\n" + "="*50)
    print("            NETWORK BARCODE SCANNER SERVER")
    print("="*50)
    print(f"Server is listening at: http://{ip}:{port}")
    print("\nPairing Info:")
    print(f"  IP Address : {ip}")
    print(f"  Port       : {port}")
    print("\n[!] Open the app on your phone, go to Settings -> Scan to Connect")
    print("    and point the camera at this terminal or enter the IP above manually.")
    print("="*50 + "\n")

if __name__ == "__main__":
    PORT = 5000
    LOCAL_IP = get_local_ip()
    print_qr_code(LOCAL_IP, PORT)
    
    server_address = ("0.0.0.0", PORT)
    try:
        with socketserver.TCPServer(server_address, PCServerHandler) as httpd:
            print("[*] Server started. Press Ctrl+C to stop.")
            httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n[*] Stopping server...")
        sys.exit(0)
    except Exception as e:
        print(f"[-] Error starting server on port {PORT}: {e}")
        print("    Make sure no other services are using port 5000.")
        sys.exit(1)
