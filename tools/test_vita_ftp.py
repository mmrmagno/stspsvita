"""Deploy over a real socket to a server that answers like VitaShell's FTP."""
import contextlib
import ftplib
import io
import socket
import sys
import tempfile
import threading
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import deploy_assets as da  # noqa: E402
import stage_data as sd  # noqa: E402


class VitaFtp:
    def __init__(self, root):
        self.root = root
        self.sock = socket.create_server(("127.0.0.1", 0))
        self.port = self.sock.getsockname()[1]
        threading.Thread(target=self._accept, daemon=True).start()

    def _accept(self):
        with contextlib.suppress(OSError):
            while True:
                conn, _ = self.sock.accept()
                threading.Thread(target=self._serve, args=(conn,), daemon=True).start()

    def _local(self, arg):
        return self.root / arg.lstrip("/").replace(":", "", 1)

    def _serve(self, conn):
        data = None

        def say(msg):
            conn.sendall((msg + "\r\n").encode())

        say("220 FTPVita Server ready.")
        for raw in conn.makefile("rb"):
            cmd, _, arg = raw.decode().rstrip("\r\n").partition(" ")
            path = self._local(arg) if arg else None
            if cmd == "USER":
                say("331 Username OK, need password b0ss.")
            elif cmd == "PASS":
                say("230 User logged in!")
            elif cmd == "TYPE":
                say("200 Okay")
            elif cmd == "PASV":
                data = socket.create_server(("127.0.0.1", 0))
                port = data.getsockname()[1]
                say(f"227 Entering Passive Mode (127,0,0,1,{port >> 8},{port & 255})")
            elif cmd in ("RETR", "STOR"):
                if cmd == "RETR" and not path.is_file():
                    data.close()
                    say("550 File not found.")
                    continue
                peer, _ = data.accept()
                say("150 Opening Image mode data transfer.")
                if cmd == "RETR":
                    peer.sendall(path.read_bytes())
                else:
                    path.write_bytes(b"".join(iter(lambda: peer.recv(65536), b"")))
                peer.close()
                data.close()
                say("226 Transfer completed.")
            elif cmd == "SIZE":
                say(f"213 {path.stat().st_size}" if path.is_file() else "550 The file doesn't exist.")
            elif cmd in ("MKD", "DELE"):
                try:
                    path.mkdir() if cmd == "MKD" else path.unlink()
                    say("226 Directory created." if cmd == "MKD" else "226 File deleted.")
                except OSError:
                    say("550 Could not do that.")
            elif cmd == "QUIT":
                say("221 Goodbye senpai :'(")
                break
            else:
                say("502 Sorry, command not implemented. :(")
        conn.close()


class VitaFtpTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dir = Path(tmp.name)
        (self.dir / "assets").mkdir()
        (self.dir / "assets/a.dds").write_bytes(b"aaaa")
        (self.dir / "assets/b.dds").write_bytes(b"bbbb")
        (self.dir / "eboot.bin").write_bytes(b"GAME")
        (self.dir / "loading.png").write_bytes(b"PNG")
        (self.dir / "vita/ux0/data").mkdir(parents=True)
        self.remote = self.dir / "vita/ux0/data/stsvita"
        self.server = VitaFtp(self.dir / "vita")
        self.addCleanup(self.server.sock.close)

    def deploy(self):
        data = sd.stage(self.dir / "eboot.bin", self.dir / "assets",
                        self.dir / "loading.png", self.dir / "out", "rev-1", "0" * 64)
        ftp = ftplib.FTP()
        ftp.connect("127.0.0.1", self.server.port, timeout=10)
        ftp.login()
        with contextlib.redirect_stdout(io.StringIO()):
            counts = da.deploy(ftp, data, da.DEFAULT_DEST)
        ftp.quit()
        return data, counts

    def tree(self, root):
        return {p.relative_to(root).as_posix(): p.read_bytes()
                for p in root.rglob("*") if p.is_file()}

    def test_install_then_update(self):
        data, _ = self.deploy()
        self.assertEqual(self.tree(self.remote), self.tree(data))
        (self.remote / "preferences").mkdir()
        (self.remote / "preferences/STSPlayer").write_bytes(b"keep")
        (self.dir / "assets/a.dds").write_bytes(b"AAAA")
        (self.dir / "assets/b.dds").unlink()
        data, (uploaded, _) = self.deploy()
        expected = self.tree(data) | {"preferences/STSPlayer": b"keep"}
        self.assertEqual(self.tree(self.remote), expected)
        self.assertEqual(uploaded, 1)


if __name__ == "__main__":
    unittest.main()
