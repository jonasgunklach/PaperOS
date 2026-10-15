#!/usr/bin/env python3
"""
Build a PaperOS ROM from a stock backup.

  tools/rom/build_rom.py <backup-name>

Reads   backups/<name>/flash/partitions/{super,vbmeta_a}.bin and build/rom/<name>/images/*.img
        (made by unpack.sh), and the recipe tools/rom/rom.txt.
Writes  build/rom/<name>/out/
          <image>.img            the edited logical partitions
          super.bin              stock super with the edited partitions written in place
          vbmeta_a-disabled.bin  stock vbmeta with verification switched off (needed after editing)
          SHA256SUMS

The stock images are ext4 without shared blocks, so they are edited in place with debugfs:
removed apps free space, added apps get mode 0644/0755, owner root and the SELinux label
`u:object_r:system_file:s0`, exactly like Bigme's own system apps. Partition sizes never change,
so the new super has the same layout and can be written straight back over the old one.
"""
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
E2 = subprocess.run(["brew", "--prefix", "e2fsprogs"], capture_output=True, text=True).stdout.strip() + "/sbin"
DEBUGFS, E2FSCK = f"{E2}/debugfs", f"{E2}/e2fsck"
LABEL = "u:object_r:system_file:s0"


def debugfs(img, commands, write=False):
    with tempfile.NamedTemporaryFile("w", suffix=".cmd", delete=False) as f:
        f.write("\n".join(commands) + "\n")
        path = f.name
    args = [DEBUGFS] + (["-w"] if write else []) + ["-f", path, img]
    out = subprocess.run(args, capture_output=True, text=True)
    os.unlink(path)
    return out.stdout + out.stderr


def listdir(img, path):
    """(name, is_dir) entries of a directory inside the image."""
    out = debugfs(img, [f"ls -p {path}"])
    entries = []
    for line in out.splitlines():
        parts = line.strip().split("/")
        # /inode/mode/uid/gid/name/size/
        if len(parts) >= 7 and parts[5] not in (".", ".."):
            entries.append((parts[5], parts[2].startswith("4")))
    return entries


def tree_commands(img, path):
    """Post-order rm/rmdir commands for a directory tree."""
    cmds = []
    for name, is_dir in listdir(img, path):
        child = f"{path}/{name}"
        if is_dir:
            cmds += tree_commands(img, child)
        else:
            cmds.append(f"rm {child}")
    cmds.append(f"rmdir {path}")
    return cmds


def exists(img, path):
    return "File not found" not in debugfs(img, [f"stat {path}"])


# Stock files carry the label NUL-terminated (26 bytes); write it byte-identically.
LABEL_FILE = os.path.join(tempfile.gettempdir(), "paperos-selinux-label")
with open(LABEL_FILE, "wb") as _f:
    _f.write(LABEL.encode() + b"\0")


def label(path, mode):
    return [
        f"set_inode_field {path} mode 0{mode}",
        f"set_inode_field {path} uid 0",
        f"set_inode_field {path} gid 0",
        f"ea_set -f {LABEL_FILE} {path} security.selinux",
    ]


def add_app(img, d, apk, work):
    """Install an APK as /<dir>/<Name>.apk, plus its arm64 native libs under lib/arm64/."""
    name = os.path.basename(d)
    cmds = [f"mkdir {d}"] + label(d, "40755")
    cmds += [f"write {apk} {d}/{name}.apk"] + label(f"{d}/{name}.apk", "100644")
    with zipfile.ZipFile(apk) as z:
        libs = [n for n in z.namelist() if n.startswith("lib/arm64-v8a/") and n.endswith(".so")]
        if libs:
            cmds += [f"mkdir {d}/lib"] + label(f"{d}/lib", "40755")
            cmds += [f"mkdir {d}/lib/arm64"] + label(f"{d}/lib/arm64", "40755")
            for n in libs:
                local = os.path.join(work, name + "-" + os.path.basename(n))
                with open(local, "wb") as f:
                    f.write(z.read(n))
                remote = f"{d}/lib/arm64/{os.path.basename(n)}"
                cmds += [f"write {local} {remote}"] + label(remote, "100644")
    return cmds


def xattr_label(img, path):
    """The raw security.selinux value of an existing file (to keep it when replacing the file)."""
    tmp = tempfile.mktemp()
    debugfs(img, [f"ea_get -f {tmp} {path} security.selinux"])
    if os.path.exists(tmp) and os.path.getsize(tmp) > 0:
        return tmp
    return LABEL_FILE


def replace_file(img, path, local):
    """Write [local] to [path], keeping the old file's label if there was one."""
    label_file = xattr_label(img, path) if exists(img, path) else LABEL_FILE
    cmds = ([f"rm {path}"] if exists(img, path) else []) + [f"write {local} {path}"]
    cmds += [f"set_inode_field {path} mode 0100644", f"set_inode_field {path} uid 0",
             f"set_inode_field {path} gid 0", f"ea_set -f {label_file} {path} security.selinux"]
    debugfs(img, cmds, write=True)


def edit_props(img, path, entries, work):
    """Set key=value lines in a build.prop-style file (replace existing keys, append new ones)."""
    local = os.path.join(work, os.path.basename(path) + ".orig")
    debugfs(img, [f"dump {path} {local}"])
    lines = open(local, encoding="utf-8", errors="replace").read().splitlines()
    for key, value in entries:
        hit = False
        for i, l in enumerate(lines):
            if l.split("=", 1)[0].strip() == key and not l.lstrip().startswith("#"):
                lines[i] = f"{key}={value}"; hit = True
        if not hit:
            lines.append(f"{key}={value}")
    new = local + ".new"
    open(new, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    replace_file(img, path, new)
    print(f"  {path}: " + ", ".join(f"{k}={v}" for k, v in entries))


def put_file(img, path, local):
    if not os.path.exists(local):
        sys.exit(f"missing {local}")
    replace_file(img, path, local)
    print(f"  {path}: added")


def free_blocks(img):
    out = subprocess.run([f"{E2}/dumpe2fs", "-h", img], capture_output=True, text=True).stdout
    return int(next(l.split(":")[1] for l in out.splitlines() if l.startswith("Free blocks")))


def main():
    name = sys.argv[1]
    images = os.path.join(ROOT, "build/rom", name, "images")
    out = os.path.join(ROOT, "build/rom", name, "out")
    flash = os.path.join(ROOT, "backups", name, "flash", "partitions")
    os.makedirs(out, exist_ok=True)
    work = tempfile.mkdtemp()

    # Parse the recipe.
    removes, apps, props, files = {}, {}, {}, {}
    for line in open(os.path.join(ROOT, "tools/rom/rom.txt")):
        line = line.split("#")[0].strip()
        if not line:
            continue
        kind, rest = line.split(None, 1)
        if kind == "remove":
            img, path = rest.split(":", 1)
            removes.setdefault(img, []).append(path)
        elif kind == "app":
            target, apk = rest.split()
            img, path = target.split(":", 1)
            apps.setdefault(img, []).append((path, os.path.join(ROOT, apk)))
        elif kind == "prop":
            target, kv = rest.split(None, 1)
            img, path = target.split(":", 1)
            key, value = kv.split("=", 1)
            props.setdefault(img, {}).setdefault(path, []).append((key.strip(), value.strip()))
        elif kind == "file":
            target, local = rest.split()
            img, path = target.split(":", 1)
            files.setdefault(img, []).append((path, os.path.join(ROOT, local)))

    touched = sorted(set(removes) | set(apps) | set(props) | set(files))
    for img_name in touched:
        src = os.path.join(images, img_name + ".img")
        dst = os.path.join(out, img_name + ".img")
        shutil.copyfile(src, dst)
        before = free_blocks(dst)
        cmds = []
        for path in removes.get(img_name, []):
            if exists(dst, path):
                cmds += tree_commands(dst, path)
            else:
                print(f"  (not present) {img_name}:{path}")
        if cmds:
            debugfs(dst, cmds, write=True)
        for path, apk in apps.get(img_name, []):
            if not os.path.exists(apk):
                sys.exit(f"missing {apk}")
            if exists(dst, path):
                debugfs(dst, tree_commands(dst, path), write=True)
            res = debugfs(dst, add_app(dst, path, apk, work), write=True)
            if "No free space" in res or "Could not allocate" in res:
                sys.exit(f"{img_name}: not enough space for {apk}")
        for path, entries in props.get(img_name, {}).items():
            edit_props(dst, path, entries, work)
        for path, local in files.get(img_name, []):
            put_file(dst, path, local)
        fsck = subprocess.run([E2FSCK, "-fy", dst], capture_output=True, text=True)
        if fsck.returncode not in (0, 1):
            sys.exit(f"{img_name}: e2fsck failed\n{fsck.stdout}{fsck.stderr}")
        after = free_blocks(dst)
        print(f"{img_name}: {len(removes.get(img_name, []))} removed, {len(apps.get(img_name, []))} added, "
              f"free {before * 4 // 1024} MB -> {after * 4 // 1024} MB")
        for path, _ in apps.get(img_name, []):
            if not exists(dst, path):
                sys.exit(f"{img_name}: {path} missing after build")

    # Write the edited partitions back into a copy of super at their extents (all single, linear).
    super_src = os.path.join(flash, "super.bin")
    super_dst = os.path.join(out, "super.bin")
    subprocess.run(["cp", "-c", super_src, super_dst], check=True)  # APFS clone: instant, no space
    extents = {}
    for line in subprocess.run([os.path.join(ROOT, "tools/rom/mtkclient/.venv/bin/python"),
                                os.path.join(ROOT, "tools/rom/bin/lpunpack.py"), "--info", super_src],
                               capture_output=True, text=True).stdout.splitlines():
        line = line.strip()
        if line.startswith("super:") and ":" in line[6:]:
            # super: <start> .. <end>: <name> (<n> sectors)
            rng, rest = line[6:].split(":", 1)
            start = int(rng.split("..")[0])
            nm = rest.strip().split()[0]
            sectors = int(rest.split("(")[1].split()[0])
            extents[nm] = (start, sectors)
    with open(super_dst, "r+b") as f:
        for img_name in touched:
            start, sectors = extents[img_name]
            data_path = os.path.join(out, img_name + ".img")
            size = os.path.getsize(data_path)
            if size != sectors * 512:
                sys.exit(f"{img_name}: image size {size} != extent {sectors * 512}")
            f.seek(start * 512)
            with open(data_path, "rb") as src:
                shutil.copyfileobj(src, f, 16 * 1024 * 1024)
            print(f"super: wrote {img_name} at sector {start} ({size // (1024 * 1024)} MB)")

    # vbmeta with verification and verity off: flags field (u32 big-endian) at offset 120.
    vb = bytearray(open(os.path.join(flash, "vbmeta_a.bin"), "rb").read())
    assert vb[:4] == b"AVB0", "vbmeta_a.bin is not an AVB image"
    vb[120:124] = struct.pack(">I", struct.unpack(">I", vb[120:124])[0] | 0x3)
    open(os.path.join(out, "vbmeta_a-disabled.bin"), "wb").write(vb)
    print("vbmeta: verification disabled (flags |= 3)")

    subprocess.run(f"cd '{out}' && shasum -a 256 super.bin vbmeta_a-disabled.bin *.img > SHA256SUMS", shell=True, check=True)
    shutil.rmtree(work, ignore_errors=True)
    print(f"ROM ready in {out}")


if __name__ == "__main__":
    main()
