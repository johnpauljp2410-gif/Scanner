import tkinter as tk
from tkinter import messagebox, scrolledtext
import socket
import threading
import subprocess
import sys
import ctypes
import time
import json
from datetime import datetime
import pyautogui
import qrcode
from PIL import ImageTk, Image
from flask import Flask, request, jsonify
from flask_cors import CORS
import pystray
from pystray import MenuItem as item

# ============================================================
# ADMIN & FIREWALL
# ============================================================
def is_admin():
    try:
        return ctypes.windll.shell32.IsUserAnAdmin()
    except:
        return False

def add_firewall_rule():
    try:
        subprocess.run(['netsh', 'advfirewall', 'firewall', 'delete', 'rule', 'name=BarcodeToPC'], capture_output=True)
        subprocess.run(['netsh', 'advfirewall', 'firewall', 'add', 'rule',
                        'name=BarcodeToPC', 'dir=in', 'action=allow',
                        'protocol=TCP', 'localport=5000'], capture_output=True)
    except Exception as e:
        print(f"Firewall rule failed: {e}")

def run_as_admin():
    if not is_admin():
        ctypes.windll.shell32.ShellExecuteW(None, "runas", sys.executable, " ".join(sys.argv), None, 1)
        sys.exit()

run_as_admin()
add_firewall_rule()

# ============================================================
# STATE
# ============================================================
scanned_count = 0
custom_name = socket.gethostname()
auto_enter = True
dark_mode = False
connected_devices = {}   # ip → {name, last_seen}
scan_log = []            # list of dicts
flask_running = False

# ============================================================
# COLORS
# ============================================================
LIGHT = {
    "bg": "#f0f2f5", "card": "#ffffff", "header": "#1e3799",
    "header_fg": "#ffffff", "fg": "#2d3436", "muted": "#636e72",
    "success": "#00b894", "log_bg": "#f8f9fa", "log_fg": "#2d3436",
    "entry_bg": "#ffffff", "btn": "#1e3799", "btn_fg": "#ffffff",
}
DARK = {
    "bg": "#1a1a2e", "card": "#16213e", "header": "#0f3460",
    "header_fg": "#e0e0e0", "fg": "#e0e0e0", "muted": "#a0a0b0",
    "success": "#00b894", "log_bg": "#0d0d1a", "log_fg": "#c0ffc0",
    "entry_bg": "#16213e", "btn": "#0f3460", "btn_fg": "#e0e0e0",
}

def C(key):
    return DARK[key] if dark_mode else LIGHT[key]

# ============================================================
# HELPERS
# ============================================================
def get_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('8.8.8.8', 80))
        return s.getsockname()[0]
    except:
        return '127.0.0.1'
    finally:
        s.close()

def now_str():
    return datetime.now().strftime("%H:%M:%S")

def get_client_ip():
    try:
        return request.remote_addr or "unknown"
    except:
        return "unknown"

# ============================================================
# FLASK SERVER
# ============================================================
flask_app = Flask(__name__)
CORS(flask_app)

def device_info():
    return jsonify({"status": "ok", "name": custom_name, "hostname": custom_name}), 200

@flask_app.route('/ping', methods=['GET'])
@flask_app.route('/discover', methods=['GET'])
@flask_app.route('/info', methods=['GET'])
@flask_app.route('/', methods=['GET'])
def discovery():
    ip = get_client_ip()
    update_connected_device(ip, ip)
    return device_info()

@flask_app.route('/scan', methods=['POST'])
def scan():
    global scanned_count
    try:
        payload = request.json or {}
    except:
        return jsonify({"status": "error", "error": "invalid json"}), 400

    barcode = str(payload.get('data', '')).strip()
    scan_type = str(payload.get('type', '')).strip()
    client_ip = get_client_ip()

    # ✅ ডেটা ভ্যালিডেশন
    if not barcode:
        return jsonify({"status": "error", "error": "no data"}), 400
    if len(barcode) > 500:
        return jsonify({"status": "error", "error": "data too long"}), 400

    scanned_count += 1
    update_connected_device(client_ip, client_ip)

    # pyautogui
    try:
        pyautogui.write(barcode, interval=0.01)
        if auto_enter:
            pyautogui.press('enter')
    except Exception as e:
        return jsonify({"status": "error", "error": str(e)}), 500

    root.after(0, lambda: update_ui_scan(barcode, scan_type, client_ip))
    return jsonify({"status": "ok"}), 200

def run_flask():
    global flask_running
    # ✅ স্মার্ট পোর্ট চেক
    import socket as _s
    test = _s.socket(_s.AF_INET, _s.SOCK_STREAM)
    try:
        test.bind(('0.0.0.0', 5000))
        test.close()
    except OSError:
        root.after(0, lambda: messagebox.showerror("Port Error",
            "Port 5000 is already in use!\nClose the other app and restart."))
        return
    flask_running = True
    # ✅ Force restart on crash
    while True:
        try:
            flask_app.run(host='0.0.0.0', port=5000, debug=False, use_reloader=False)
        except Exception as e:
            print(f"Flask crashed: {e}, restarting in 2s...")
            time.sleep(2)

# ============================================================
# UI UPDATE FUNCTIONS
# ============================================================
def update_ui_scan(barcode, scan_type, client_ip):
    global scan_log
    entry = {"time": now_str(), "barcode": barcode, "type": scan_type, "ip": client_ip}
    scan_log.insert(0, entry)
    if len(scan_log) > 100:
        scan_log = scan_log[:100]

    # Log box
    log_box.config(state=tk.NORMAL)
    tag = f"[{entry['time']}] {barcode}"
    if scan_type:
        tag += f" ({scan_type})"
    log_box.insert(tk.END, tag + "\n")
    log_box.see(tk.END)
    log_box.config(state=tk.DISABLED)

    # Counter
    count_label.config(text=f"Total Scans: {scanned_count}")
    last_label.config(text=f"Last: {barcode}", fg=C("success"))

def update_connected_device(ip, name):
    connected_devices[ip] = {"name": name, "last_seen": now_str()}
    root.after(0, refresh_devices_ui)

def refresh_devices_ui():
    devices_box.config(state=tk.NORMAL)
    devices_box.delete("1.0", tk.END)
    if not connected_devices:
        devices_box.insert(tk.END, "No devices connected yet\n")
    else:
        for ip, info in connected_devices.items():
            devices_box.insert(tk.END, f"🟢 {info['name']} — {ip} (last: {info['last_seen']})\n")
    devices_box.config(state=tk.DISABLED)

def update_qr():
    ip = get_ip()
    qr_data = f"http://{ip}:5000?name={custom_name}"
    qr_img = qrcode.make(qr_data).resize((180, 180))
    new_qr = ImageTk.PhotoImage(qr_img)
    qr_label.config(image=new_qr)
    qr_label.image = new_qr

def save_name():
    global custom_name
    new_name = name_entry.get().strip()
    if not new_name:
        messagebox.showwarning("Warning", "Name cannot be empty!")
        return
    custom_name = new_name
    save_btn.config(text="✅ Saved!")
    root.after(2000, lambda: save_btn.config(text="💾 Save"))
    update_qr()

def toggle_auto_enter():
    global auto_enter
    auto_enter = not auto_enter
    enter_btn.config(text=f"Auto-Enter: {'ON ✅' if auto_enter else 'OFF ❌'}")

def toggle_dark_mode():
    global dark_mode
    dark_mode = not dark_mode
    apply_theme()

def apply_theme():
    root.configure(bg=C("bg"))
    header_frame.configure(bg=C("header"))
    header_label.configure(bg=C("header"), fg=C("header_fg"))
    ip_frame.configure(bg=C("bg"))
    ip_label.configure(bg=C("bg"), fg=C("fg"))
    fw_label.configure(bg=C("bg"))
    name_frame.configure(bg=C("card"))
    name_label.configure(bg=C("card"), fg=C("muted"))
    name_entry.configure(bg=C("entry_bg"), fg=C("fg"))
    save_btn.configure(bg=C("btn"), fg=C("btn_fg"))
    qr_outer.configure(bg=C("bg"))
    qr_frame.configure(bg=C("card"))
    stats_frame.configure(bg=C("bg"))
    count_label.configure(bg=C("bg"), fg=C("fg"))
    last_label.configure(bg=C("bg"))
    btns_frame.configure(bg=C("bg"))
    enter_btn.configure(bg=C("btn"), fg=C("btn_fg"))
    dark_btn.configure(bg=C("btn"), fg=C("btn_fg"))
    log_frame.configure(bg=C("bg"))
    log_title.configure(bg=C("bg"), fg=C("fg"))
    log_box.configure(bg=C("log_bg"), fg=C("log_fg"))
    dev_frame.configure(bg=C("bg"))
    dev_title.configure(bg=C("bg"), fg=C("fg"))
    devices_box.configure(bg=C("log_bg"), fg=C("log_fg"))
    footer_frame.configure(bg=C("header"))
    footer_label.configure(bg=C("header"), fg=C("header_fg"))

# ============================================================
# SYSTEM TRAY
# ============================================================
def create_tray_icon():
    try:
        img = Image.new('RGB', (64, 64), color="#1e3799")
        icon = pystray.Icon("BarcodeToPC", img, "Barcode to PC",
                            menu=pystray.Menu(
                                item('Show', lambda: root.after(0, show_window)),
                                item('Quit', lambda: root.after(0, quit_app))
                            ))
        icon.run()
    except Exception as e:
        print(f"Tray failed: {e}")

def hide_to_tray():
    root.withdraw()
    threading.Thread(target=create_tray_icon, daemon=True).start()

def show_window():
    root.deiconify()
    root.lift()

def quit_app():
    root.destroy()
    sys.exit()

# ============================================================
# UI SETUP
# ============================================================
root = tk.Tk()
root.title("Barcode to PC — Scanner by Rifat")
root.geometry("440x820")
root.resizable(False, True)
root.configure(bg=C("bg"))
root.protocol("WM_DELETE_WINDOW", hide_to_tray)

# Header
header_frame = tk.Frame(root, bg=C("header"))
header_frame.pack(fill="x")
header_label = tk.Label(header_frame, text="PC Barcode Receiver",
                        font=("Arial", 17, "bold"), bg=C("header"), fg=C("header_fg"), pady=14)
header_label.pack()

# IP
ip_frame = tk.Frame(root, bg=C("bg"), pady=4)
ip_frame.pack()
ip_label = tk.Label(ip_frame, text=f"IP: {get_ip()}  |  Port: 5000",
                    font=("Arial", 10), bg=C("bg"), fg=C("fg"))
ip_label.pack()

fw_label = tk.Label(root, text="🛡️ Firewall: Auto-configured",
                    font=("Arial", 8), bg=C("bg"), fg="#27ae60")
fw_label.pack()

# Server Name
name_frame = tk.Frame(root, bg=C("card"), padx=12, pady=8, relief="groove", bd=1)
name_frame.pack(fill="x", padx=16, pady=(8, 0))
name_label = tk.Label(name_frame, text="Server Name", font=("Arial", 8, "bold"),
                      bg=C("card"), fg=C("muted"))
name_label.pack(anchor="w")
entry_row = tk.Frame(name_frame, bg=C("card"))
entry_row.pack(fill="x", pady=(3, 0))
name_entry = tk.Entry(entry_row, font=("Arial", 11), bd=1, relief="solid",
                      bg=C("entry_bg"), fg=C("fg"))
name_entry.insert(0, custom_name)
name_entry.pack(side="left", fill="x", expand=True, ipady=4)
save_btn = tk.Button(entry_row, text="💾 Save", font=("Arial", 9, "bold"),
                     bg=C("btn"), fg=C("btn_fg"), bd=0, padx=10,
                     cursor="hand2", command=save_name)
save_btn.pack(side="left", padx=(6, 0), ipady=4)

# QR Code
qr_outer = tk.Frame(root, bg=C("bg"))
qr_outer.pack(pady=8)
qr_frame = tk.Frame(qr_outer, bg=C("card"), padx=8, pady=8, relief="groove", bd=2)
qr_frame.pack()
qr_data_init = f"http://{get_ip()}:5000?name={custom_name}"
qr_img_init = qrcode.make(qr_data_init).resize((180, 180))
tk_qr = ImageTk.PhotoImage(qr_img_init)
qr_label = tk.Label(qr_frame, image=tk_qr, bg=C("card"))
qr_label.pack()
tk.Label(qr_frame, text="Scan to connect from phone",
         font=("Arial", 8), bg=C("card"), fg="#636e72").pack()

# Stats
stats_frame = tk.Frame(root, bg=C("bg"))
stats_frame.pack(pady=(4, 0))
count_label = tk.Label(stats_frame, text="Total Scans: 0",
                       font=("Arial", 13, "bold"), bg=C("bg"), fg=C("fg"))
count_label.pack()
last_label = tk.Label(stats_frame, text="Waiting for first scan...",
                      font=("Arial", 9), bg=C("bg"), fg=C("muted"))
last_label.pack()

# Buttons
btns_frame = tk.Frame(root, bg=C("bg"))
btns_frame.pack(pady=6)
enter_btn = tk.Button(btns_frame, text="Auto-Enter: ON ✅",
                      font=("Arial", 9, "bold"), bg=C("btn"), fg=C("btn_fg"),
                      bd=0, padx=10, pady=4, cursor="hand2", command=toggle_auto_enter)
enter_btn.pack(side="left", padx=4)
dark_btn = tk.Button(btns_frame, text="🌙 Dark Mode",
                     font=("Arial", 9, "bold"), bg=C("btn"), fg=C("btn_fg"),
                     bd=0, padx=10, pady=4, cursor="hand2", command=toggle_dark_mode)
dark_btn.pack(side="left", padx=4)

# Connected Devices
dev_frame = tk.Frame(root, bg=C("bg"))
dev_frame.pack(fill="x", padx=16, pady=(6, 0))
dev_title = tk.Label(dev_frame, text="📱 Connected Devices",
                     font=("Arial", 9, "bold"), bg=C("bg"), fg=C("fg"))
dev_title.pack(anchor="w")
devices_box = scrolledtext.ScrolledText(dev_frame, height=3, font=("Consolas", 8),
                                        bg=C("log_bg"), fg=C("log_fg"),
                                        state=tk.DISABLED, bd=1, relief="solid")
devices_box.pack(fill="x")
devices_box.config(state=tk.NORMAL)
devices_box.insert(tk.END, "No devices connected yet\n")
devices_box.config(state=tk.DISABLED)

# Live Log
log_frame = tk.Frame(root, bg=C("bg"))
log_frame.pack(fill="both", expand=True, padx=16, pady=(6, 0))
log_title = tk.Label(log_frame, text="📋 Scan Log",
                     font=("Arial", 9, "bold"), bg=C("bg"), fg=C("fg"))
log_title.pack(anchor="w")
log_box = scrolledtext.ScrolledText(log_frame, height=8, font=("Consolas", 8),
                                    bg=C("log_bg"), fg=C("log_fg"),
                                    state=tk.DISABLED, bd=1, relief="solid")
log_box.pack(fill="both", expand=True)

# Footer — Scanner By Rifat
footer_frame = tk.Frame(root, bg=C("header"))
footer_frame.pack(fill="x", side="bottom")
footer_label = tk.Label(footer_frame, text="Scanner By Rifat",
                        font=("Arial", 11, "bold italic"),
                        bg=C("header"), fg=C("header_fg"), pady=8)
footer_label.pack()

# ============================================================
# START
# ============================================================
threading.Thread(target=run_flask, daemon=True).start()
root.mainloop()
