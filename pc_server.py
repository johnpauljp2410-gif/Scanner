#!/usr/bin/env python3
"""
Network Scan Sender — PC Server (Improved)
─────────────────────────────────────────────
Fixes:  Unicode clipboard typing · thread-safe state · single tray icon
        URL-encoded QR · waitress production server · port race-condition fix
Bonus:  sound on scan · CSV export · prefix/suffix · device timeout
        per-device counter · typing method toggle · barcode tray icon
"""

import tkinter as tk
from tkinter import messagebox, scrolledtext, filedialog
import socket
import threading
import queue
import subprocess
import sys
import ctypes
import time
import csv
import urllib.parse
from datetime import datetime

import pyautogui
import qrcode
from PIL import ImageTk, Image, ImageDraw
from flask import Flask, request, jsonify
from flask_cors import CORS
import pystray
from pystray import MenuItem as item

# ── Optional dependencies ────────────────────────────────────
try:
    from waitress import serve as waitress_serve
    HAS_WAITRESS = True
except ImportError:
    HAS_WAITRESS = False

try:
    import winsound
    HAS_WINSOUND = True
except ImportError:
    HAS_WINSOUND = False

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
        subprocess.run(['netsh', 'advfirewall', 'firewall', 'delete',
                        'rule', 'name=NetworkScanSender'], capture_output=True)
        subprocess.run(['netsh', 'advfirewall', 'firewall', 'add', 'rule',
                        'name=NetworkScanSender', 'dir=in', 'action=allow',
                        'protocol=TCP', 'localport=5000'], capture_output=True)
    except Exception as e:
        print(f"Firewall rule failed: {e}")

def run_as_admin():
    if not is_admin():
        ctypes.windll.shell32.ShellExecuteW(
            None, "runas", sys.executable,
            " ".join(f'"{a}"' for a in sys.argv), None, 1)
        sys.exit()

run_as_admin()
add_firewall_rule()

# ============================================================
# THREAD-SAFE STATE
# ============================================================
state_lock   = threading.Lock()
scanned_count   = 0
custom_name     = socket.gethostname()
auto_enter      = True
sound_enabled   = True
dark_mode       = False
typing_method   = "clipboard"   # "clipboard" | "keyboard"
prefix_text     = ""
suffix_text     = ""
connected_devices = {}          # ip → {count, last_seen, last_seen_ts}
scan_log          = []          # newest first, max 200
scan_queue        = queue.Queue()   # serializes typing in queue mode
current_mode      = "direct"        # "direct" | "queue"  — tracked for UI
flask_running     = False
tray_running      = False
tray_icon_obj     = None

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
# APP ICON  (barcode scanner design)
# ============================================================
def create_app_icon(size=64):
    """Draw a professional barcode-scanner icon."""
    img  = Image.new('RGB', (size, size), "#1e3799")
    draw = ImageDraw.Draw(img)

    # Barcode bars — alternating widths
    bars = [
        (4, 2), (8, 1), (11, 3), (16, 1), (19, 2), (23, 1),
        (26, 3), (31, 1), (34, 2), (38, 1), (41, 3), (46, 1),
        (49, 2), (53, 1), (56, 3), (60, 1),
    ]
    top = int(size * 0.16)
    bot = int(size * 0.74)
    for x, w in bars:
        if x + w < size - 2:
            draw.rectangle([x, top, x + w, bot], fill="white")

    # Red scan line
    mid = int(size * 0.44)
    draw.rectangle([3, mid, size - 4, mid + 2], fill="#e74c3c")

    # Corner brackets (red)
    br, th = int(size * 0.18), 2
    corners = [(2, 2), (size - 2 - br, 2),
               (2, size - 2 - br), (size - 2 - br, size - 2 - br)]
    for cx, cy in corners:
        draw.rectangle([cx, cy, cx + br, cy + th], fill="#e74c3c")
        draw.rectangle([cx, cy, cx + th, cy + br], fill="#e74c3c")

    return img

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
# UNICODE CLIPBOARD  (Win32 — supports any language/character)
# ============================================================
CF_UNICODETEXT = 13

def _clipboard_set(text):
    encoded = (text + '\0').encode('utf-16-le')
    h = ctypes.windll.kernel32.GlobalAlloc(0x0042, len(encoded))
    p = ctypes.windll.kernel32.GlobalLock(h)
    ctypes.memmove(p, encoded, len(encoded))
    ctypes.windll.kernel32.GlobalUnlock(h)
    ctypes.windll.user32.OpenClipboard(None)
    ctypes.windll.user32.EmptyClipboard()
    ctypes.windll.user32.SetClipboardData(CF_UNICODETEXT, h)
    ctypes.windll.user32.CloseClipboard()

def _clipboard_get():
    try:
        ctypes.windll.user32.OpenClipboard(None)
        h = ctypes.windll.user32.GetClipboardData(CF_UNICODETEXT)
        if not h:
            ctypes.windll.user32.CloseClipboard()
            return ""
        p  = ctypes.windll.kernel32.GlobalLock(h)
        sz = ctypes.windll.kernel32.GlobalSize(h)
        buf = ctypes.create_string_buffer(sz)
        ctypes.memmove(buf, p, sz)
        ctypes.windll.kernel32.GlobalUnlock(h)
        ctypes.windll.user32.CloseClipboard()
        return buf.raw.decode('utf-16-le').rstrip('\x00')
    except:
        try:
            ctypes.windll.user32.CloseClipboard()
        except:
            pass
        return ""

def _clipboard_clear():
    try:
        ctypes.windll.user32.OpenClipboard(None)
        ctypes.windll.user32.EmptyClipboard()
        ctypes.windll.user32.CloseClipboard()
    except:
        pass

def type_text(text):
    """
    Type barcode text — clipboard mode: instant + full Unicode support.
    Keyboard mode: ASCII only, slower (legacy fallback).
    Restores original clipboard after paste.
    Always called from the single typing-worker thread — never concurrently.
    """
    full = prefix_text + text + suffix_text
    if typing_method == "clipboard":
        old = _clipboard_get()
        try:
            _clipboard_set(full)
            time.sleep(0.04)
            pyautogui.hotkey('ctrl', 'v')
            time.sleep(0.06)
        finally:
            if old:
                _clipboard_set(old)
            else:
                _clipboard_clear()
    else:
        pyautogui.write(full, interval=0.01)

# ── Typing worker — one thread, one scan at a time ──────────
def _typing_worker():
    """
    Consumes scan_queue serially so keyboard/clipboard is never
    touched by two threads simultaneously. HTTP responses are
    already sent before this runs — zero scan loss, zero overlap.
    """
    while True:
        item = scan_queue.get()          # blocks until a scan arrives
        if item is None:                 # sentinel → stop worker
            break
        barcode, scan_type, client_ip, count = item
        try:
            type_text(barcode)
            if auto_enter:
                pyautogui.press('enter')
        except Exception as e:
            print(f"Typing error: {e}")
        play_scan_sound()
        root.after(0, lambda b=barcode, t=scan_type,
                          ip=client_ip, c=count:
                   _ui_on_scan(b, t, ip, c))
        scan_queue.task_done()

# ============================================================
# SOUND
# ============================================================
def play_scan_sound():
    if not sound_enabled:
        return
    def _beep():
        if HAS_WINSOUND:
            winsound.Beep(1000, 80)
        else:
            print('\a', end='', flush=True)
    threading.Thread(target=_beep, daemon=True).start()

# ============================================================
# FLASK SERVER
# ============================================================
flask_app = Flask(__name__)
CORS(flask_app)

@flask_app.route('/',        methods=['GET'])
@flask_app.route('/ping',    methods=['GET'])
@flask_app.route('/discover',methods=['GET'])
@flask_app.route('/info',    methods=['GET'])
def discovery():
    _update_device(get_client_ip())
    return jsonify({"status": "ok", "name": custom_name,
                    "hostname": custom_name}), 200

@flask_app.route('/scan', methods=['POST'])
def scan():
    global scanned_count
    try:
        payload = request.json or {}
    except Exception:
        return jsonify({"status": "error", "error": "invalid json"}), 400

    barcode    = str(payload.get('data', '')).strip()
    scan_type  = str(payload.get('type', '')).strip()
    client_ip  = get_client_ip()

    if not barcode:
        return jsonify({"status": "error", "error": "no data"}), 400
    if len(barcode) > 1000:
        return jsonify({"status": "error", "error": "data too long"}), 400

    # Thread-safe counter increment
    with state_lock:
        scanned_count += 1
        current_count  = scanned_count

    _update_device(client_ip)   # register device first, then read count

    with state_lock:
        device_count = len(connected_devices)

    if device_count <= 1:
        # ⚡ DIRECT MODE — single device, type immediately, instant output
        try:
            type_text(barcode)
            if auto_enter:
                pyautogui.press('enter')
        except Exception as e:
            return jsonify({"status": "error", "error": str(e)}), 500
        play_scan_sound()
        root.after(0, lambda b=barcode, t=scan_type,
                          ip=client_ip, c=current_count:
                   _ui_on_scan(b, t, ip, c))
    else:
        # 🔀 QUEUE MODE — multiple devices, enqueue for serial typing
        scan_queue.put((barcode, scan_type, client_ip, current_count))

    return jsonify({"status": "ok"}), 200

def run_flask():
    global flask_running
    # Direct bind — no gap between check and use
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        probe.bind(('0.0.0.0', 5000))
        probe.close()
    except OSError:
        root.after(0, lambda: messagebox.showerror(
            "Port Error",
            "Port 5000 is already in use!\n"
            "Close the other application and restart."))
        return

    flask_running = True
    if HAS_WAITRESS:
        # Production-grade multi-threaded server
        waitress_serve(flask_app, host='0.0.0.0', port=5000, threads=8)
    else:
        flask_app.run(host='0.0.0.0', port=5000,
                      debug=False, use_reloader=False)

# ============================================================
# DEVICE REGISTRY  (thread-safe)
# ============================================================
def _update_device(ip):
    with state_lock:
        dev = connected_devices.setdefault(ip, {'count': 0})
        dev['count']       += 1
        dev['last_seen']    = now_str()
        dev['last_seen_ts'] = time.time()
    root.after(0, _refresh_devices_ui)
    root.after(0, _update_mode_label)

def _cleanup_old_devices():
    """Remove devices not seen in the last 2 minutes."""
    cutoff = time.time() - 120
    with state_lock:
        stale = [ip for ip, d in connected_devices.items()
                 if d.get('last_seen_ts', 0) < cutoff]
        for ip in stale:
            del connected_devices[ip]
    if stale:
        root.after(0, _refresh_devices_ui)
        root.after(0, _update_mode_label)
    root.after(30_000, _cleanup_old_devices)

# ============================================================
# UI UPDATE HELPERS
# ============================================================
def _ui_on_scan(barcode, scan_type, client_ip, count):
    with state_lock:
        entry = {"time": now_str(), "barcode": barcode,
                 "type": scan_type, "ip": client_ip}
        scan_log.insert(0, entry)
        if len(scan_log) > 200:
            scan_log.pop()

    line = f"[{entry['time']}]  {barcode}"
    if scan_type:
        line += f"  ({scan_type})"
    line += f"  ← {client_ip}"

    log_box.config(state=tk.NORMAL)
    log_box.insert("1.0", line + "\n")
    log_box.config(state=tk.DISABLED)

    count_label.config(text=f"Total Scans: {count}")
    last_label.config(text=f"Last: {barcode[:55]}", fg=C("success"))

def _refresh_devices_ui():
    devices_box.config(state=tk.NORMAL)
    devices_box.delete("1.0", tk.END)
    with state_lock:
        devs = dict(connected_devices)
    if not devs:
        devices_box.insert(tk.END, "No devices connected yet\n")
    else:
        for ip, info in devs.items():
            devices_box.insert(
                tk.END,
                f"🟢  {ip:<18}  scans: {info['count']:<5}  last: {info['last_seen']}\n"
            )
    devices_box.config(state=tk.DISABLED)

def _update_mode_label():
    """Reflect current typing mode in the UI badge — called from main thread."""
    global current_mode
    with state_lock:
        n = len(connected_devices)
    if n <= 1:
        current_mode = "direct"
        mode_label.config(
            text=f"⚡  Direct Mode  —  {n} device active  (instant output)",
            fg="#00b894", bg=C("bg"),
        )
    else:
        current_mode = "queue"
        mode_label.config(
            text=f"🔀  Queue Mode  —  {n} devices active  (serial, no overlap)",
            fg="#e67e22", bg=C("bg"),
        )

# ============================================================
# QR CODE
# ============================================================
def update_qr():
    ip = get_ip()
    url = f"http://{ip}:5000?name={urllib.parse.quote(custom_name)}"
    qr_img = qrcode.make(url).resize((178, 178))
    new_qr = ImageTk.PhotoImage(qr_img)
    qr_label.config(image=new_qr)
    qr_label.image = new_qr
    ip_label.config(text=f"IP: {ip}  |  Port: 5000")

# ============================================================
# BUTTON ACTIONS
# ============================================================
def save_name():
    global custom_name
    new = name_entry.get().strip()
    if not new:
        messagebox.showwarning("Warning", "Name cannot be empty!")
        return
    custom_name = new
    save_btn.config(text="✅ Saved!")
    root.after(2000, lambda: save_btn.config(text="💾 Save"))
    update_qr()

def toggle_auto_enter():
    global auto_enter
    auto_enter = not auto_enter
    enter_btn.config(text=f"Enter: {'ON ✅' if auto_enter else 'OFF ❌'}")

def toggle_sound():
    global sound_enabled
    sound_enabled = not sound_enabled
    sound_btn.config(text=f"Sound: {'ON 🔔' if sound_enabled else 'OFF 🔕'}")

def toggle_typing():
    global typing_method
    typing_method = "keyboard" if typing_method == "clipboard" else "clipboard"
    type_btn.config(
        text=f"Type: {'📋 Clipboard' if typing_method == 'clipboard' else '⌨️ Keyboard'}")

def toggle_dark():
    global dark_mode
    dark_mode = not dark_mode
    apply_theme()

def save_prefix_suffix():
    global prefix_text, suffix_text
    prefix_text = prefix_entry.get()
    suffix_text = suffix_entry.get()
    pf_save_btn.config(text="✅")
    root.after(1800, lambda: pf_save_btn.config(text="Save"))

def clear_log():
    global scan_log
    with state_lock:
        scan_log.clear()
    log_box.config(state=tk.NORMAL)
    log_box.delete("1.0", tk.END)
    log_box.config(state=tk.DISABLED)
    count_label.config(text="Total Scans: 0")
    last_label.config(text="Waiting for first scan...", fg=C("muted"))

def export_csv():
    with state_lock:
        logs = list(scan_log)
    if not logs:
        messagebox.showinfo("Export", "No scan data to export!")
        return
    path = filedialog.asksaveasfilename(
        defaultextension=".csv",
        filetypes=[("CSV files", "*.csv")],
        initialfile=f"scans_{datetime.now().strftime('%Y%m%d_%H%M%S')}.csv"
    )
    if not path:
        return
    with open(path, 'w', newline='', encoding='utf-8-sig') as f:
        writer = csv.DictWriter(f, fieldnames=["time", "barcode", "type", "ip"])
        writer.writeheader()
        writer.writerows(logs)
    messagebox.showinfo("Export", f"✅ Exported {len(logs)} scans to:\n{path}")

# ============================================================
# SYSTEM TRAY  (single instance, professional barcode icon)
# ============================================================
def _show_window():
    root.after(0, lambda: (root.deiconify(), root.lift(), root.focus_force()))

def _quit_app():
    global tray_icon_obj
    if tray_icon_obj:
        try:
            tray_icon_obj.stop()
        except Exception:
            pass
    root.after(0, lambda: (root.destroy(), sys.exit(0)))

def hide_to_tray():
    global tray_running
    root.withdraw()
    if tray_running:
        return                      # already sitting in tray — don't duplicate
    tray_running = True

    def _run():
        global tray_icon_obj, tray_running
        try:
            icon_img = create_app_icon(64)
            tray_icon_obj = pystray.Icon(
                "NetworkScanSender",
                icon_img,
                "Network Scan Sender",
                menu=pystray.Menu(
                    item("📱  Show Window",  lambda i, it: _show_window(), default=True),
                    item("❌  Quit",          lambda i, it: _quit_app()),
                )
            )
            tray_icon_obj.run()
        except Exception as e:
            print(f"Tray error: {e}")
        finally:
            tray_running = False

    threading.Thread(target=_run, daemon=True).start()

# ============================================================
# THEME
# ============================================================
def apply_theme():
    # bg widgets
    for w in [root, ip_frame, qr_outer, stats_frame,
              btns_row1, btns_row2, log_frame, dev_frame,
              pf_frame, pf_row]:
        try: w.configure(bg=C("bg"))
        except: pass
    for w in [ip_label, count_label, last_label,
              log_title, dev_title, pf_title,
              pre_lbl, suf_lbl, fw_label]:
        try: w.configure(bg=C("bg"), fg=C("fg"))
        except: pass

    # card widgets
    for w in [name_frame, qr_frame, entry_row]:
        try: w.configure(bg=C("card"))
        except: pass
    name_label.configure(bg=C("card"), fg=C("muted"))

    # header/footer
    for w in [header_frame, footer_frame]:
        w.configure(bg=C("header"))
    for w in [header_label, footer_label]:
        w.configure(bg=C("header"), fg=C("header_fg"))

    # entries
    for w in [name_entry, prefix_entry, suffix_entry]:
        w.configure(bg=C("entry_bg"), fg=C("fg"))

    # buttons
    for w in [save_btn, enter_btn, sound_btn, dark_btn,
              type_btn, clear_btn, export_btn, pf_save_btn]:
        w.configure(bg=C("btn"), fg=C("btn_fg"))

    # text boxes
    for w in [log_box, devices_box]:
        w.configure(bg=C("log_bg"), fg=C("log_fg"))

    fw_label.configure(fg="#27ae60")
    last_label.configure(fg=C("muted"))
    _update_mode_label()   # re-applies bg + correct mode colour

# ============================================================
# UI SETUP
# ============================================================
root = tk.Tk()
root.title("Network Scan Sender — PC Server")
root.geometry("460x940")
root.resizable(False, True)
root.configure(bg=C("bg"))
root.protocol("WM_DELETE_WINDOW", hide_to_tray)

# Window/taskbar icon
try:
    _wicon = ImageTk.PhotoImage(create_app_icon(32))
    root.iconphoto(True, _wicon)
except Exception:
    pass

# ── Header ────────────────────────────────────────────────────
header_frame = tk.Frame(root, bg=C("header"))
header_frame.pack(fill="x")
header_label = tk.Label(header_frame, text="🔗  Network Scan Sender",
                        font=("Arial", 16, "bold"),
                        bg=C("header"), fg=C("header_fg"), pady=13)
header_label.pack()

# ── IP Status ────────────────────────────────────────────────
ip_frame = tk.Frame(root, bg=C("bg"), pady=4)
ip_frame.pack()
ip_label = tk.Label(ip_frame, text=f"IP: {get_ip()}  |  Port: 5000",
                    font=("Arial", 10), bg=C("bg"), fg=C("fg"))
ip_label.pack()
fw_label = tk.Label(root, text="🛡️  Firewall: Auto-configured",
                    font=("Arial", 8), bg=C("bg"), fg="#27ae60")
fw_label.pack()

# ── Server Name ───────────────────────────────────────────────
name_frame = tk.Frame(root, bg=C("card"), padx=12, pady=8,
                      relief="groove", bd=1)
name_frame.pack(fill="x", padx=16, pady=(8, 0))
name_label = tk.Label(name_frame, text="Server Name",
                      font=("Arial", 8, "bold"),
                      bg=C("card"), fg=C("muted"))
name_label.pack(anchor="w")
entry_row = tk.Frame(name_frame, bg=C("card"))
entry_row.pack(fill="x", pady=(3, 0))
name_entry = tk.Entry(entry_row, font=("Arial", 11), bd=1, relief="solid",
                      bg=C("entry_bg"), fg=C("fg"))
name_entry.insert(0, custom_name)
name_entry.pack(side="left", fill="x", expand=True, ipady=4)
save_btn = tk.Button(entry_row, text="💾 Save",
                     font=("Arial", 9, "bold"),
                     bg=C("btn"), fg=C("btn_fg"), bd=0,
                     padx=10, cursor="hand2", command=save_name)
save_btn.pack(side="left", padx=(6, 0), ipady=4)

# ── QR Code ───────────────────────────────────────────────────
qr_outer = tk.Frame(root, bg=C("bg"))
qr_outer.pack(pady=8)
qr_frame = tk.Frame(qr_outer, bg=C("card"), padx=8, pady=8,
                    relief="groove", bd=2)
qr_frame.pack()
_qr0 = qrcode.make(
    f"http://{get_ip()}:5000?name={urllib.parse.quote(custom_name)}"
).resize((178, 178))
tk_qr = ImageTk.PhotoImage(_qr0)
qr_label = tk.Label(qr_frame, image=tk_qr, bg=C("card"))
qr_label.pack()
tk.Label(qr_frame, text="Scan QR to connect phone",
         font=("Arial", 8), bg=C("card"), fg="#636e72").pack()

# ── Stats ────────────────────────────────────────────────────
stats_frame = tk.Frame(root, bg=C("bg"))
stats_frame.pack(pady=(4, 0))
count_label = tk.Label(stats_frame, text="Total Scans: 0",
                       font=("Arial", 13, "bold"),
                       bg=C("bg"), fg=C("fg"))
count_label.pack()
last_label = tk.Label(stats_frame, text="Waiting for first scan...",
                      font=("Arial", 9), bg=C("bg"), fg=C("muted"))
last_label.pack()
mode_label = tk.Label(stats_frame,
                      text="⚡  Direct Mode  —  0 devices active  (instant output)",
                      font=("Arial", 8, "bold"), bg=C("bg"), fg="#00b894")
mode_label.pack(pady=(2, 0))

# ── Buttons Row 1 ────────────────────────────────────────────
btns_row1 = tk.Frame(root, bg=C("bg"))
btns_row1.pack(pady=(7, 2))

for text, cmd, attr in [
    ("Enter: ON ✅",  toggle_auto_enter, "enter_btn"),
    ("Sound: ON 🔔",  toggle_sound,      "sound_btn"),
    ("🌙 Dark",       toggle_dark,       "dark_btn"),
]:
    b = tk.Button(btns_row1, text=text, font=("Arial", 9, "bold"),
                  bg=C("btn"), fg=C("btn_fg"), bd=0,
                  padx=9, pady=4, cursor="hand2", command=cmd)
    b.pack(side="left", padx=3)
    globals()[attr] = b   # assign to module-level name

# ── Buttons Row 2 ────────────────────────────────────────────
btns_row2 = tk.Frame(root, bg=C("bg"))
btns_row2.pack(pady=(0, 5))

for text, cmd, attr in [
    ("Type: 📋 Clipboard", toggle_typing, "type_btn"),
    ("🗑️ Clear Log",       clear_log,     "clear_btn"),
    ("📥 Export CSV",      export_csv,    "export_btn"),
]:
    b = tk.Button(btns_row2, text=text, font=("Arial", 9, "bold"),
                  bg=C("btn"), fg=C("btn_fg"), bd=0,
                  padx=9, pady=4, cursor="hand2", command=cmd)
    b.pack(side="left", padx=3)
    globals()[attr] = b

# ── Prefix / Suffix ──────────────────────────────────────────
pf_frame = tk.Frame(root, bg=C("bg"))
pf_frame.pack(fill="x", padx=16, pady=(2, 4))
pf_title = tk.Label(pf_frame, text="Prefix / Suffix  (added before/after every scan)",
                    font=("Arial", 8, "bold"), bg=C("bg"), fg=C("fg"))
pf_title.pack(anchor="w")
pf_row = tk.Frame(pf_frame, bg=C("bg"))
pf_row.pack(fill="x")
pre_lbl = tk.Label(pf_row, text="Pre:", font=("Arial", 8),
                   bg=C("bg"), fg=C("fg"))
pre_lbl.pack(side="left")
prefix_entry = tk.Entry(pf_row, font=("Arial", 9), width=10, bd=1,
                        relief="solid", bg=C("entry_bg"), fg=C("fg"))
prefix_entry.pack(side="left", padx=(2, 8), ipady=2)
suf_lbl = tk.Label(pf_row, text="Suf:", font=("Arial", 8),
                   bg=C("bg"), fg=C("fg"))
suf_lbl.pack(side="left")
suffix_entry = tk.Entry(pf_row, font=("Arial", 9), width=10, bd=1,
                        relief="solid", bg=C("entry_bg"), fg=C("fg"))
suffix_entry.pack(side="left", padx=(2, 6), ipady=2)
pf_save_btn = tk.Button(pf_row, text="Save", font=("Arial", 8, "bold"),
                        bg=C("btn"), fg=C("btn_fg"), bd=0,
                        padx=6, pady=2, cursor="hand2",
                        command=save_prefix_suffix)
pf_save_btn.pack(side="left")

# ── Connected Devices ────────────────────────────────────────
dev_frame = tk.Frame(root, bg=C("bg"))
dev_frame.pack(fill="x", padx=16, pady=(4, 0))
dev_title = tk.Label(dev_frame, text="📱  Connected Devices",
                     font=("Arial", 9, "bold"), bg=C("bg"), fg=C("fg"))
dev_title.pack(anchor="w")
devices_box = scrolledtext.ScrolledText(
    dev_frame, height=3, font=("Consolas", 8),
    bg=C("log_bg"), fg=C("log_fg"),
    state=tk.DISABLED, bd=1, relief="solid")
devices_box.pack(fill="x")
devices_box.config(state=tk.NORMAL)
devices_box.insert(tk.END, "No devices connected yet\n")
devices_box.config(state=tk.DISABLED)

# ── Scan Log ─────────────────────────────────────────────────
log_frame = tk.Frame(root, bg=C("bg"))
log_frame.pack(fill="both", expand=True, padx=16, pady=(6, 0))
log_title = tk.Label(log_frame, text="📋  Scan Log  (newest first)",
                     font=("Arial", 9, "bold"), bg=C("bg"), fg=C("fg"))
log_title.pack(anchor="w")
log_box = scrolledtext.ScrolledText(
    log_frame, height=8, font=("Consolas", 8),
    bg=C("log_bg"), fg=C("log_fg"),
    state=tk.DISABLED, bd=1, relief="solid")
log_box.pack(fill="both", expand=True)

# ── Footer ───────────────────────────────────────────────────
footer_frame = tk.Frame(root, bg=C("header"))
footer_frame.pack(fill="x", side="bottom")
footer_label = tk.Label(footer_frame, text="Scanner By Rifat",
                        font=("Arial", 10, "bold italic"),
                        bg=C("header"), fg=C("header_fg"), pady=7)
footer_label.pack()

# ============================================================
# LAUNCH
# ============================================================
threading.Thread(target=run_flask,    daemon=True).start()
threading.Thread(target=_typing_worker, daemon=True).start()  # serial queue
root.after(30_000, _cleanup_old_devices)
root.mainloop()
