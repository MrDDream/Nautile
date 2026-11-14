#!/usr/bin/env python3
"""Third-party licence files shipped with the apps.

  scripts/gen-licenses.py android [--deps FILE]
      app/src/main/assets/licenses/: NOTICE.md, dependencies.txt (the libraries of the release APK, read
      from their POMs in the Gradle cache) and the licence texts they need. FILE is the output of
      `./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath`; without it the script runs
      that command (JAVA_HOME must point to a JDK 17).
  scripts/gen-licenses.py desktop
      desktop/packaging/THIRD-PARTY-LICENSES.txt: NOTICE.md, the font licences and the licences of every
      crate, from `cargo about` (cargo install --locked --features cli cargo-about; CARGO_ABOUT overrides it).

Missing SPDX licence texts are downloaded once from github.com/spdx/license-list-data and then kept in the
repository with the assets.
"""
import os
import re
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
ASSETS = os.path.join(ROOT, 'app/src/main/assets/licenses')
DESKTOP_OUT = os.path.join(ROOT, 'desktop/packaging/THIRD-PARTY-LICENSES.txt')
GRADLE_CACHE = os.path.join(os.environ.get('GRADLE_USER_HOME', os.path.expanduser('~/.gradle')), 'caches/modules-2/files-2.1')
SPDX_TEXT = 'https://raw.githubusercontent.com/spdx/license-list-data/main/text/%s.txt'

# Font licences next to NOTICE.md (they carry the fonts' copyright lines).
FONT_LICENSES = ['JetBrainsMono-OFL.txt', 'FiraCode-OFL.txt', 'SourceCodePro-OFL.txt', 'CascadiaCode-OFL.txt', 'Hack-LICENSE.md']
DESKTOP_FONT_LICENSES = ['Inter-OFL.txt'] + FONT_LICENSES

# Licences named in NOTICE.md but not in any POM (FreeRDP, OpenSSL, cJSON, uriparser, Termux, MPL files).
NOTICE_SPDX = ['Apache-2.0', 'MPL-2.0', 'MIT', 'BSD-3-Clause']

# POM licence name or URL → SPDX id (first match wins, lower-case).
SPDX_RULES = [
    (r'apache.*2|apache-2|licenses/license-2\.0', 'Apache-2.0'),
    (r'bouncy ?castle', 'MIT'),
    (r'\bmit\b|opensource\.org/licenses/mit', 'MIT'),
    (r'bsd.?3|new bsd|revised bsd|bsd-3-clause', 'BSD-3-Clause'),
    (r'bsd.?2|simplified bsd', 'BSD-2-Clause'),
    (r'eclipse public license.*2|epl-2', 'EPL-2.0'),
    (r'eclipse public license|epl-1|epl-v10', 'EPL-1.0'),
    (r'eclipse distribution license|edl-v10', 'BSD-3-Clause'),
    (r'mozilla public license.*2|mpl.?2', 'MPL-2.0'),
    (r'cc0', 'CC0-1.0'),
    (r'unlicense', 'Unlicense'),
    (r'lesser general public license.*2\.1|lgpl.?2\.1', 'LGPL-2.1-only'),
    (r'general public license.*2.*classpath|gpl.*classpath', 'GPL-2.0-with-classpath-exception'),
    (r'\bbsd\b', 'BSD-3-Clause'),
]

# Libraries whose POM has no usable licence: group:artifact → SPDX id, checked by hand.
KNOWN = {
    'net.zetetic:sqlcipher-android': 'BSD-3-Clause',  # SQLCipher Community Edition, BSD-style (zetetic.net/sqlcipher/license)
    'com.github.mik3y:usb-serial-for-android': 'MIT',  # github.com/mik3y/usb-serial-for-android/blob/master/LICENSE.txt
}


def fail(msg):
    sys.exit('gen-licenses: ' + msg)


def spdx_of(name, url):
    text = ('%s %s' % (name or '', url or '')).lower()
    for pattern, spdx in SPDX_RULES:
        if re.search(pattern, text):
            return spdx
    return None


def ensure_spdx_text(spdx, folder):
    os.makedirs(folder, exist_ok=True)
    path = os.path.join(folder, spdx + '.txt')
    if not os.path.exists(path):
        print('downloading the %s text' % spdx)
        with urllib.request.urlopen(SPDX_TEXT % spdx, timeout=30) as r:
            data = r.read()
        with open(path, 'wb') as f:
            f.write(data)
    return path


def strip_ns(tree):
    for el in tree.iter():
        if isinstance(el.tag, str) and '}' in el.tag:
            el.tag = el.tag.split('}', 1)[1]
    return tree


def find_pom(group, artifact, version):
    folder = os.path.join(GRADLE_CACHE, group, artifact, version)
    if os.path.isdir(folder):
        for sub in sorted(os.listdir(folder)):
            pom = os.path.join(folder, sub, '%s-%s.pom' % (artifact, version))
            if os.path.exists(pom):
                return pom
    return None


def pom_info(group, artifact, version, depth=0):
    """(project name, project URL, [(licence name, licence URL)]) following the parent POMs."""
    pom = find_pom(group, artifact, version)
    if pom is None:
        return None, None, []
    root = strip_ns(ET.parse(pom)).getroot()
    name = (root.findtext('name') or '').strip() or None
    url = (root.findtext('url') or '').strip() or None
    licenses = [((l.findtext('name') or '').strip(), (l.findtext('url') or '').strip()) for l in root.findall('licenses/license')]
    parent = root.find('parent')
    if (not licenses or not url) and parent is not None and depth < 5:
        p_name, p_url, p_lic = pom_info(parent.findtext('groupId'), parent.findtext('artifactId'), parent.findtext('version'), depth + 1)
        url = url or p_url
        licenses = licenses or p_lic
    return name, url, licenses


def android_dependencies(deps_file):
    if deps_file:
        with open(deps_file, encoding='utf-8') as f:
            out = f.read()
    else:
        out = subprocess.run([os.path.join(ROOT, 'gradlew'), '-q', ':app:dependencies', '--configuration', 'releaseRuntimeClasspath'],
                             cwd=ROOT, check=True, capture_output=True, text=True).stdout
    coords = set()
    for line in out.splitlines():
        m = re.search(r'--- ([\w.\-]+):([\w.\-]+):([\w.\-]+)(?: -> ([\w.\-]+))?', line)
        if m:
            coords.add((m.group(1), m.group(2), m.group(4) or m.group(3)))
        else:
            m = re.search(r'--- ([\w.\-]+):([\w.\-]+) -> ([\w.\-]+)', line)
            if m:
                coords.add((m.group(1), m.group(2), m.group(3)))
    if not coords:
        fail('no dependency found in the Gradle output')
    return sorted(coords)


def android(deps_file):
    os.makedirs(ASSETS, exist_ok=True)
    rows, used, unknown = [], set(NOTICE_SPDX), []
    for group, artifact, version in android_dependencies(deps_file):
        name, url, licenses = pom_info(group, artifact, version)
        ids = []
        for l_name, l_url in licenses:
            spdx = spdx_of(l_name, l_url)
            if spdx and spdx not in ids:
                ids.append(spdx)
        key = '%s:%s' % (group, artifact)
        if not ids and key in KNOWN:
            ids = [KNOWN[key]]
        if not ids:
            unknown.append('%s:%s (%s)' % (key, version, ', '.join(n for n, _ in licenses) or 'no licence in the POM'))
            continue
        used.update(ids)
        rows.append((key, version, name, url, ids))
    if unknown:
        fail('licence not recognised, add it to KNOWN or SPDX_RULES:\n  ' + '\n  '.join(unknown))

    lines = ['Libraries in the Nautile APK', '============================', '',
             'Generated by scripts/gen-licenses.py android from the POM files of the release build.',
             'The licence texts are in this folder (<SPDX id>.txt); NOTICE.md lists the code and fonts kept in the',
             'repository (Termux, FreeRDP, OpenSSL, cJSON, uriparser, Nerd Fonts, Material Design Icons).', '']
    for key, version, name, url, ids in rows:
        lines.append('%s %s' % (key, version))
        detail = ' OR '.join(ids)
        if name and name != key.split(':')[1]:
            detail = '%s · %s' % (name, detail)
        lines.append('  ' + detail)
        if url:
            lines.append('  ' + url)
        lines.append('')
    with open(os.path.join(ASSETS, 'dependencies.txt'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines))

    with open(os.path.join(ROOT, 'third_party/NOTICE.md'), encoding='utf-8') as f:
        notice = f.read()
    with open(os.path.join(ASSETS, 'NOTICE.md'), 'w', encoding='utf-8') as f:
        f.write(notice)
    for font in FONT_LICENSES:
        with open(os.path.join(ROOT, 'third_party', font), 'rb') as src, open(os.path.join(ASSETS, font), 'wb') as dst:
            dst.write(src.read())
    keep = {'NOTICE.md', 'dependencies.txt'} | set(FONT_LICENSES) | {s + '.txt' for s in used}
    for spdx in sorted(used):
        ensure_spdx_text(spdx, ASSETS)
    for stale in sorted(set(os.listdir(ASSETS)) - keep):
        os.remove(os.path.join(ASSETS, stale))
    print('%d libraries, licences: %s' % (len(rows), ', '.join(sorted(used))))


def desktop():
    about = os.environ.get('CARGO_ABOUT')
    cmd = [about] if about else ['cargo', 'about']
    crates = subprocess.run(cmd + ['generate', '--locked', '--workspace', '--fail', 'packaging/licenses/about.hbs'],
                            cwd=os.path.join(ROOT, 'desktop'), check=True, capture_output=True, text=True).stdout
    parts = ['Nautile Desktop: third-party licences', '=====================================', '',
             'Nautile is free software under the GNU GPL v3 or later (LICENSE). It contains the code, fonts and',
             'crates listed below, under their own licences.', '']
    with open(os.path.join(ROOT, 'third_party/NOTICE.md'), encoding='utf-8') as f:
        parts += ['', '#' * 80, '# third_party/NOTICE.md', '#' * 80, '', f.read()]
    for font in DESKTOP_FONT_LICENSES:
        with open(os.path.join(ROOT, 'third_party', font), encoding='utf-8') as f:
            parts += ['', '#' * 80, '# Font licence: ' + font, '#' * 80, '', f.read()]
    with open(ensure_spdx_text('Apache-2.0', ASSETS), encoding='utf-8') as f:
        parts += ['', '#' * 80, '# Material Icons (Google), Material Design Icons (Pictogrammers): Apache-2.0', '#' * 80, '', f.read()]
    parts += ['', '#' * 80, '# Rust crates', '#' * 80, '', crates]
    text = '\n'.join(parts)
    text = re.sub(r'[ \t]+\n', '\n', text).rstrip('\n') + '\n'
    with open(DESKTOP_OUT, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)
    print('%s: %d KB' % (os.path.relpath(DESKTOP_OUT, ROOT), len(text.encode()) // 1024))


def main():
    args = sys.argv[1:]
    if not args or args[0] not in ('android', 'desktop', 'all'):
        fail('usage: gen-licenses.py android [--deps FILE] | desktop | all')
    deps = None
    if '--deps' in args:
        deps = args[args.index('--deps') + 1]
    if args[0] in ('android', 'all'):
        android(deps)
    if args[0] in ('desktop', 'all'):
        desktop()


if __name__ == '__main__':
    main()
