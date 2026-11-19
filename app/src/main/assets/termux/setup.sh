set -eu
printf '\nDan 1.155.20 · preparazione archivio integrato\n'
command -v python >/dev/null && command -v rclone >/dev/null || pkg install -y python rclone
if [ ! -d "$HOME/storage/shared" ]; then
  termux-setup-storage
  printf 'Se Android chiede accesso ai file, consenti. Premi Invio quando hai finito.\n'
  read -r archive_storage_ready
fi
if [ ! -f "$HOME/.config/dan-files/config.json" ]; then
  printf '\nServe un remote rclone Google Drive. Se esiste già puoi saltare questa fase.\nAprire la configurazione Google Drive? [s/N] '
  read -r archive_configure
  case "$archive_configure" in s|S) rclone config ;; esac
  python "$HOME/.local/share/dan-files-15520/files_bridge.py" --init
fi
# Do not kill a server already using this port, or change existing configuration.
if python -c "import urllib.request,json; r=json.load(urllib.request.urlopen('http://127.0.0.1:8766/health',timeout=2)); assert r.get('service')=='Dan Files' and r.get('version')=='1.1'" 2>/dev/null; then
  printf '\nArchivio già attivo. Torna in Dan e premi Verifica collegamento.\n'
  exit 0
fi
mkdir -p "$HOME/.config/dan-files"
nohup python "$HOME/.local/share/dan-files-15520/files_bridge.py" </dev/null >>"$HOME/.config/dan-files/service.log" 2>&1 &
printf '\nAvvio richiesto. Torna in Dan e premi Verifica collegamento.\nSe la porta è occupata dal vecchio servizio, fermalo prima di riprovare.\n'
