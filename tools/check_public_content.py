#!/usr/bin/env python3
"""Check staged data or outgoing Git history before publishing public content."""
import argparse
import io
import json
import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path

def git(*args, check=True):
    result=subprocess.run(['git',*args],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    if check and result.returncode:
        raise RuntimeError('Git content could not be read; publication blocked.')
    return result

ROOT=Path(git('rev-parse','--show-toplevel').stdout.decode().strip())
os.chdir(ROOT)
POLICY=json.loads((ROOT/'.public-content.json').read_text(encoding='utf8'))
ALLOW_EMAILS=set(x.lower() for x in POLICY.get('public_emails',[]))
REMOVED=POLICY.get('private_path_prefixes',[])
ERRORS=set()
DRAFT=re.compile(r'(?i)(?:^|/)(?:design|drafts?|scratch|verification|workflow|handoff|audits?|reports?|captures|outputs|backups|_private-backups|\.codex[^/]*)(?:/|$)|(?:^|/)(?:AGENTS|CLAUDE|MEMORY|SKILL)\.md$|(?:_draft|_草稿)\.')
LOCAL_PATH=re.compile(r'(?i)\b[A-Z]:[\\/]Users[\\/](?!Public[\\/]|Default[\\/]|user[\\/]|username[\\/]|example[\\/])[^\\/\r\n\x00<>]{1,64}[\\/]|(?<![\w/:])/(?:Users|home)/(?!runner(?:/|$)|build(?:/|$)|user(?:/|$)|username(?:/|$)|example(?:/|$))[^/\r\n\x00<>]{1,64}/')
TOKEN=re.compile(r'\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}|AKIA[0-9A-Z]{16}|sk-(?:proj-)?[A-Za-z0-9_-]{40,}|sb_secret_[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{20,})\b')
KEY=re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----\s*\n[A-Za-z0-9+/=\r\n]{64,}')
EMAIL=re.compile(r'(?<![\w.+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}(?![\w.-])')
NOREPLY=re.compile(r'^(?:[^@]+@users\.noreply\.github\.com|noreply@github\.com)$',re.I)

def fail(path,rule):
    ERRORS.add((path,rule))

def check_path(path):
    low=path.lower()
    if DRAFT.search(path) or any(path==p.rstrip('/') or path.startswith(p) for p in REMOVED):
        fail(path,'internal draft or working record')
    if (Path(path).name.startswith('.env') and not low.endswith(('.example','.sample'))) or low.endswith(('.pfx','.p12','.jks','.keystore','.pdb')) or Path(path).name in ('local.properties','keystore.properties','credentials.json'):
        fail(path,'local credentials or machine configuration')
    if low.endswith(('.exe','.dll','.msi','.apk','.aab','.zip','.7z')) and path not in POLICY.get('allowed_binary_paths',[]):
        fail(path,'generated artifact belongs in a checked release, not source Git')

def public_email(value,path):
    value=value.lower()
    if value in ALLOW_EMAILS or NOREPLY.fullmatch(value):return True
    if value.endswith(('.png','.svg','.jpg','.webp')):return True
    if value.rsplit('@',1)[-1] in ('example.com','example.org','example.net','test.com','test.local','localhost','invalid'):return True
    if '/third_party/' in '/'+path or '/third-party/' in '/'+path:return True
    if 'license' in path.lower() or Path(path).name in ('OFL.txt','COPYING'):return True
    return False

def check_bytes(data,path,depth=0):
    binary=b'\x00' in data[:8192] or Path(path).suffix.lower() in ('.png','.jpg','.jpeg','.ico','.woff','.woff2','.jar','.exe','.dll','.msi','.apk')
    texts=[data.decode('utf8','replace')]
    if binary:texts.append(data.decode('utf-16-le','ignore'))
    for text in texts:
        if LOCAL_PATH.search(text):fail(path,'personal local directory')
        if KEY.search(text) or TOKEN.search(text):fail(path,'private key or access token')
        if not binary:
            for match in EMAIL.finditer(text):
                if not public_email(match.group(),path):fail(path,'email requires explicit public-contact approval')
        username=os.environ.get('USERNAME') or os.environ.get('USER')
        if username and len(username)>=4 and username.lower() not in ('runner','runneradmin','root','user','build'):
            if re.search(r'(?<![A-Za-z0-9_])'+re.escape(username)+r'(?![A-Za-z0-9_])',text,re.I):fail(path,'local account identifier')
    if data.startswith(b'PK\x03\x04') and depth<2:
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                for item in archive.infolist():
                    if item.is_dir():continue
                    if item.file_size>30_000_000:
                        fail(path,'archive member exceeds scan limit');continue
                    check_bytes(archive.read(item),path+'!'+item.filename,depth+1)
        except (zipfile.BadZipFile,RuntimeError,NotImplementedError):fail(path,'archive could not be inspected')

def entries(revision=None):
    args=('ls-tree','-rz',revision) if revision else ('ls-files','-s','-z')
    for item in git(*args).stdout.split(b'\x00'):
        if not item:continue
        info,path=item.split(b'\t',1)
        fields=info.split()
        oid=fields[2] if revision else fields[1]
        yield oid.decode(),path.decode('utf8','replace')

def identity(commit):
    raw=git('cat-file','commit',commit).stdout
    for line in raw.split(b'\n\n',1)[0].splitlines():
        if line.startswith((b'author ',b'committer ')):
            match=re.search(rb'<([^>]+)>',line)
            if not match or not NOREPLY.fullmatch(match.group(1).decode('utf8','replace')):fail('commit metadata','use a GitHub noreply email')

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--staged',action='store_true')
    parser.add_argument('--all-history',action='store_true')
    parser.add_argument('--pre-push',action='store_true')
    opts=parser.parse_args()
    revisions=[]
    if opts.pre_push:
        for line in sys.stdin:
            local_ref,local,remote_ref,remote=line.split()
            if set(local)=={'0'}:continue
            args=['rev-list',local]
            if set(remote)!={'0'} and git('cat-file','-e',remote,check=False).returncode==0:args.append('^'+remote)
            revisions+=git(*args).stdout.decode().splitlines()
    elif opts.all_history:revisions=git('rev-list','HEAD').stdout.decode().splitlines()
    seen=set(); checked=0
    for revision in list(dict.fromkeys(revisions)) if revisions else ([None] if not opts.pre_push else []):
        if revision:identity(revision)
        for oid,path in entries(revision):
            check_path(path)
            if (oid,path) in seen:continue
            seen.add((oid,path));checked+=1
            check_bytes(git('cat-file','blob',oid).stdout,path)
    if opts.staged:
        email=git('var','GIT_AUTHOR_IDENT').stdout.decode()
        match=re.search(r'<([^>]+)>',email)
        if not match or not NOREPLY.fullmatch(match.group(1)):fail('commit metadata','use a GitHub noreply email')
    if ERRORS:
        for path,rule in sorted(ERRORS):print(f'BLOCKED: {path}: {rule}',file=sys.stderr)
        return 1
    print(f'Public-content check passed ({checked} file versions).')
    return 0

if __name__=='__main__':
    try:sys.exit(main())
    except Exception:
        print('BLOCKED: privacy check could not finish; inspect the local checker before publishing.',file=sys.stderr)
        sys.exit(1)
