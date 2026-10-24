#!/usr/bin/env python3
"""Manual read-only inspection of the vendor's EDT export bundle.

No compilation, no writes to user EDT/IB, no publishing of vendor jars.
Only class signatures and selected invocation names appear in CI logs.
"""
import base64
import io
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
import lzma

ROOT = "https://edt.1c.ru/downloads/releases/ruby/2026.2/"
USER = os.environ["P2_USER"]
TOKEN = os.environ["P2_TOKEN"]
AUTH = "Basic " + base64.b64encode((USER + ":" + TOKEN).encode()).decode()
TARGET_ID = "com._1c.g5.v8.dt.export"
MAX_BYTES = 64 * 1024 * 1024


class StayOnVendorHost(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urllib.parse.urlsplit(newurl).hostname != "edt.1c.ru":
            raise RuntimeError("Vendor repository redirected to an unexpected host")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


OPENER = urllib.request.build_opener(StayOnVendorHost())


def get(url, optional=False):
    if urllib.parse.urlsplit(url).hostname != "edt.1c.ru":
        raise RuntimeError("Non-vendor URL rejected")
    req = urllib.request.Request(url, headers={"Authorization": AUTH})
    try:
        with OPENER.open(req, timeout=45) as response:
            data = response.read(MAX_BYTES + 1)
        if len(data) > MAX_BYTES:
            raise RuntimeError("Vendor metadata or artifact exceeds safety limit")
        return data
    except urllib.error.HTTPError as ex:
        if optional and ex.code in (401, 403, 404):
            return None
        raise RuntimeError("Vendor repository request failed: HTTP " + str(ex.code)) from None


def decode_metadata(name, payload):
    if name.endswith(".jar"):
        with zipfile.ZipFile(io.BytesIO(payload)) as archive:
            xml_name = next((x for x in archive.namelist() if x.endswith(".xml")), None)
            if xml_name is None:
                raise RuntimeError("No XML inside vendor p2 metadata jar")
            payload = archive.read(xml_name)
    elif name.endswith(".xz"):
        payload = lzma.decompress(payload)
    return ET.fromstring(payload)


def metadata(repo_url, base_names):
    for name in base_names:
        body = get(urllib.parse.urljoin(repo_url, name), optional=True)
        if body is not None:
            try:
                return decode_metadata(name, body)
            except (ValueError, ET.ParseError, zipfile.BadZipFile):
                raise RuntimeError("Vendor metadata cannot be parsed: " + name) from None
    return None


def tag(element):
    return element.tag.rsplit("}", 1)[-1]


def find_artifacts(url, depth=0, visited=None):
    if visited is None:
        visited = set()
    if depth > 4 or url in visited or len(visited) > 25:
        return []
    visited.add(url)
    composite = metadata(url, (
        "compositeArtifacts.jar", "compositeArtifacts.xml",
        "compositeArtifacts.xml.xz"))
    if composite is not None:
        children = [x.attrib.get("location") for x in composite.iter()
                    if tag(x) == "child" and x.attrib.get("location")]
        print("Composite p2 layer:", depth, "children:", len(children))
        all_artifacts = []
        for location in children:
            child = urllib.parse.urljoin(url, location.rstrip("/") + "/")
            all_artifacts.extend(find_artifacts(child, depth + 1, visited))
        return all_artifacts
    root = metadata(url, ("artifacts.jar", "artifacts.xml.xz", "artifacts.xml"))
    if root is None:
        return []
    items = []
    for x in root.iter():
        if tag(x) != "artifact":
            continue
        identifier = x.attrib.get("id", "")
        if identifier == TARGET_ID and x.attrib.get("classifier") == "osgi.bundle":
            items.append((url, identifier, x.attrib.get("version", "")))
    return items


def summarize_javap(jar_file, classname, methods):
    result = subprocess.run(
        ["javap", "-p", "-classpath", str(jar_file), classname],
        capture_output=True, text=True, timeout=35, check=False)
    if result.returncode:
        print("MISSING", classname, "in downloaded EDT bundle")
        return
    print("CLASS", classname)
    for line in result.stdout.splitlines():
        if any(token in line for token in methods) and ("(" in line or ";" in line):
            print("  ", line.strip()[:240])
    details = subprocess.run(
        ["javap", "-c", "-p", "-classpath", str(jar_file), classname],
        capture_output=True, text=True, timeout=35, check=False)
    # Only report symbolic invocation targets, not vendor bytecode.
    if details.returncode == 0:
        symbols = set(re.findall(
            r"// (?:InterfaceMethod|Method) ([^\\n]+)", details.stdout))
        interesting = [x[:180] for x in sorted(symbols)
                       if any(key in x.lower() for key in
                              ("inject", "guice", "export", "getinstance", "service"))]
        print("  Referenced methods (selected):", interesting[:28])


def main():
    if not USER or not TOKEN:
        raise RuntimeError("P2 credentials are missing")
    print("Read-only probe of authenticated EDT 2026.2 p2; no Maven/build")
    artifacts = find_artifacts(ROOT)
    if not artifacts:
        raise RuntimeError("Cannot locate com._1c.g5.v8.dt.export in authenticated p2 metadata")
    print("Native EDT export versions:", sorted(set(x[2] for x in artifacts))[-5:])
    repo_url, bundle_id, version = sorted(artifacts, key=lambda x: x[2])[-1]
    data = get(urllib.parse.urljoin(repo_url, "plugins/" + bundle_id
                                    + "_" + version + ".jar"))
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        names = set(z.namelist())
        classes = ("com/_1c/g5/v8/dt/internal/export/ExportPlugin.class",
                   "com/_1c/g5/v8/dt/internal/export/ExportOperationFactory.class",
                   "com/_1c/g5/v8/dt/export/ExportRuntimeModule.class")
        print("Required native classes present:", {x: x in names for x in classes})
        manifest = z.read("META-INF/MANIFEST.MF").decode("utf-8", errors="replace")
        for key in ("Bundle-SymbolicName", "Bundle-Activator", "Bundle-Version"):
            match = re.search(r"^" + re.escape(key) + r":\\s*(.*)$", manifest, re.M)
            print(key + ":", match.group(1).strip() if match else "(not declared)")
    with tempfile.TemporaryDirectory(prefix="edt-export-probe-") as directory:
        path = pathlib.Path(directory) / "native-export.jar"
        path.write_bytes(data)
        summarize_javap(path,
            "com._1c.g5.v8.dt.internal.export.ExportPlugin",
            ("getDefault", "getInjector", "start("))
        summarize_javap(path,
            "com._1c.g5.v8.dt.internal.export.ExportOperationFactory",
            ("ExportOperationFactory(", "createExportOperation(", "IExport"))
        summarize_javap(path,
            "com._1c.g5.v8.dt.export.ExportRuntimeModule",
            ("ExportRuntimeModule(", "configure(", "bind"))
        summarize_javap(path,
            "com._1c.g5.v8.dt.export.IExportOperationFactory",
            ("createExportOperation(",))
    print("Probe finished. Vendor binary was not uploaded or retained.")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print("DIAGNOSTIC FAILED:", type(exc).__name__, str(exc), file=sys.stderr)
        sys.exit(1)
