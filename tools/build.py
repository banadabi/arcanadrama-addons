#!/usr/bin/env python3
"""Build only changed addon inputs and prepare a catalogue; never push or publish."""
from pathlib import Path
import argparse, hashlib, json, os, shutil, subprocess, sys, tempfile, zipfile
import release

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / 'release-state.json'
CACHE = ROOT / '.release-cache'
BASE = 'https://raw.githubusercontent.com/banadabi/arcanadrama-addons/builds'
SHARED = ['tools/release.py', 'tools/build.py', 'build.gradle.kts', 'settings.gradle.kts',
          'gradle.properties', 'gradlew', 'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties']

def sha(data): return hashlib.sha256(data).hexdigest()

def sync_inputs(modules):
    inputs = {name: sha((ROOT / name).read_bytes()) for name in SHARED}
    data = (json.dumps(inputs, sort_keys=True, indent=2) + '\n').encode()
    for name in modules:
        target = ROOT / name / 'release-inputs.json'
        if target.is_symlink(): raise ValueError('Source input link refused')
        if not target.exists() or target.read_bytes() != data:
            release._atomic_write(target, data, 0o644)

def hydrate(checkout):
    if not STATE.exists(): return
    for name, entry in json.loads(STATE.read_text())['plugins'].items():
        artifact = entry.get('artifact')
        if not artifact: continue
        destination = (STATE.parent / artifact['path']).resolve()
        if not destination.is_relative_to(CACHE.resolve()): raise ValueError('Cache path outside owned area')
        if destination.exists():
            if destination.is_symlink() or sha(destination.read_bytes()) != artifact['sha256']:
                raise ValueError('Existing cache integrity mismatch')
            continue
        source = checkout / (name + '.cs3')
        if source.is_symlink(): raise ValueError('Published artifact link refused')
        data = source.read_bytes()
        if sha(data) != artifact['sha256'] or len(data) != artifact['size']:
            raise ValueError('Published artifact integrity mismatch')
        release._freeze(destination, data)

def build_one(name):
    module = ROOT / name
    subprocess.run(['bash', './gradlew', ':provider:assembleRelease'], cwd=module, check=True, timeout=3600)
    gradle_cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle')))
    builders = list((gradle_cache / 'caches/modules-2/files-2.1/com.android.tools.build/builder/9.4.1').glob('*/*.jar'))
    if len(builders) != 1: raise ValueError('Pinned AGP9.4.1 D8 not found')
    sdk_value = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk_value: raise ValueError('ANDROID_HOME or ANDROID_SDK_ROOT is required')
    sdk = Path(sdk_value)
    platforms = [sdk / 'platforms' / p / 'android.jar' for p in ['android-37.0', 'android-37']]
    android_jar = next((p for p in platforms if p.is_file()), None)
    if android_jar is None: raise ValueError('Android37 platform not found')
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    shim = module / 'compile-api/build/intermediates/compile_library_classes_jar/release/bundleLibCompileToJarRelease/classes.jar'
    CACHE.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='build-', dir=CACHE) as temporary:
        work = Path(temporary)
        with zipfile.ZipFile(module / 'provider/build/outputs/aar/provider-release.aar') as aar:
            (work / 'classes.jar').write_bytes(aar.read('classes.jar'))
        subprocess.run([java, '-cp', str(builders[0]), 'com.android.tools.r8.D8', '--release', '--min-api', '26',
                        '--lib', str(android_jar), '--classpath', str(shim), '--output', str(work),
                        str(work / 'classes.jar')], check=True, timeout=300)
        output = work / (name + '.cs3')
        release.pack_cs3(module / 'manifest.json', work / 'classes.dex', output)
        return release.register(module, output, STATE, CACHE)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--module', action='append')
    parser.add_argument('--hydrate', type=Path)
    parser.add_argument('--plan', action='store_true')
    parser.add_argument('--build', action='store_true')
    parser.add_argument('--catalogue', action='store_true')
    parser.add_argument('--output', type=Path, default=ROOT / 'dist')
    parser.add_argument('--github-output', action='store_true')
    args = parser.parse_args()
    metadata = json.loads((ROOT / 'addon-metadata.json').read_text())
    known = [x['internalName'] for x in metadata]
    names = args.module or known
    if not names or len(set(names)) != len(names) or any(n not in known for n in names):
        raise ValueError('Unknown or duplicate source module')
    sync_inputs(names)
    published = args.hydrate or (ROOT / '.published' if (ROOT / '.published').is_dir() else None)
    if published: hydrate(published.resolve())
    plans = [release.prepare(ROOT / name, STATE) for name in names]
    needed = any(p['build_required'] for p in plans)
    state = json.loads(STATE.read_text())
    for plan in plans:
        if plan['build_required'] and state['plugins'][plan['internalName']].get('artifact'):
            raise ValueError('Frozen artifact is missing. Check out builds to .published or use --hydrate before building.')
    if args.github_output:
        with open(os.environ['GITHUB_OUTPUT'], 'a') as stream:
            stream.write('build_required=' + str(needed).lower() + '\n')
    if args.build:
        for plan in plans:
            if plan['build_required']: build_one(plan['internalName'])
    catalogue = None
    if args.catalogue:
        catalogue = release.catalogue(STATE, ROOT / 'addon-metadata.json', BASE, args.output)
        review = ROOT / 'review-bundle'
        review.mkdir(exist_ok=True)
        shutil.copy2(STATE, review / 'release-state.json')
        for name in known:
            target = review / name
            target.mkdir(exist_ok=True)
            shutil.copy2(ROOT / name / 'manifest.json', target / 'manifest.json')
            shutil.copy2(ROOT / name / 'release-inputs.json', target / 'release-inputs.json')
    print(json.dumps({'plan': plans, 'build_required': needed, 'catalogue': catalogue, 'published': False}, ensure_ascii=False))

if __name__ == '__main__': main()
