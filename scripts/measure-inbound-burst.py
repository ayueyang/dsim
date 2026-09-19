"""400-message MQTT/Room regression. Test devices only; never sends carrier SMS.

Requires paho-mqtt, cryptography and an already configured debug app. Supply
DSIM_TEST_PASSWORD via environment; it is never written to evidence. Uses the
batch-I DSM3/SmsSync fixture, independent witness, and real Room snapshots.
Elapsed time is an observation upper bound (includes adb/snapshot/poll costs),
not a per-message latency measurement. Kill mode includes downtime/relaunch.
"""
import argparse
import base64
import contextlib
import hashlib
import json
import os
from pathlib import Path
import secrets
import sqlite3
import subprocess
import threading
import time
import uuid

import paho.mqtt.client as mqtt
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--label', required=True)
    p.add_argument('--mode', choices=['nokill', 'kill'], required=True)
    p.add_argument('--serial', default='emulator-5554')
    p.add_argument('--adb', default='adb')
    p.add_argument('--broker', default='127.0.0.1')
    p.add_argument('--topic', required=True)
    p.add_argument('--evidence', required=True)
    p.add_argument('--count', type=int, default=400)
    p.add_argument('--kill-after', type=float, default=1.0)
    p.add_argument('--timeout', type=float, default=300)
    a = p.parse_args()
    password = os.environ['DSIM_TEST_PASSWORD']
    key = hashlib.pbkdf2_hmac('sha256', password.encode(), b'dSIM/v3/master-key', 120000, 32)
    pkg = 'com.example.dsim'
    tag = 'F3-' + a.label + '-' + a.mode + '-' + uuid.uuid4().hex[:8] + '-'
    peer = 'probe-' + tag.rstrip('-')
    topic = a.topic + '/' + peer
    folder = Path(a.evidence) / tag.rstrip('-')
    folder.mkdir(parents=True, exist_ok=False)
    trace = []

    def log(event, **values):
        record = dict(event=event, monotonic=time.monotonic(), **values)
        trace.append(record)
        print(json.dumps(record), flush=True)

    def adb(*args, optional=False):
        result = subprocess.run([a.adb, '-s', a.serial, *args], capture_output=True, timeout=45)
        if result.returncode and not optional:
            raise RuntimeError(result.stderr.decode('utf-8', 'replace'))
        return result

    def text(*args):
        return adb(*args).stdout.decode('utf-8', 'replace')

    def rows():
        # Always close SQLite before replacing the prior snapshot (Windows).
        # Do not copy a live shm: SQLite rebuilds its own wal index.
        path = folder / 'dsim_core_database'
        for suffix in ('-wal', '-shm'):
            Path(str(path) + suffix).unlink(missing_ok=True)
        # A checkpoint can change the main DB between reading it and its WAL.
        # Bracket the WAL read with identical main-DB reads; discard racing samples.
        for attempt in range(5):
            before = adb('exec-out', 'run-as', pkg, 'cat', 'databases/dsim_core_database').stdout
            wal = adb('exec-out', 'run-as', pkg, 'cat', 'databases/dsim_core_database-wal', optional=True)
            after = adb('exec-out', 'run-as', pkg, 'cat', 'databases/dsim_core_database').stdout
            if before == after:
                path.write_bytes(after)
                if wal.returncode == 0:
                    Path(str(path) + '-wal').write_bytes(wal.stdout)
                break
            log('discard_checkpoint_race', attempt=attempt + 1)
        else:
            return None  # Busy writer: no sample, not a zero count; outer deadline stays bounded.
        with contextlib.closing(sqlite3.connect(path)) as con:
            return con.execute('SELECT count(*), count(DISTINCT uuid) FROM sms_messages WHERE uuid LIKE ?',
                               (tag + '%',)).fetchone()

    def launch():
        text('shell', 'am', 'start', '-n', pkg + '/.SmsListActivity')

    def wait_subscribed():
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            output = text('logcat', '-d', '-s', 'dSIM_SyncService:D', '*:S')
            if 'subscribed ' + a.topic in output:
                return
            time.sleep(1)
        raise RuntimeError('No fresh subscribed log after launch')

    seen = set()
    subscribed = threading.Event()
    witness_lock = threading.Lock()

    def on_message(client, userdata, msg):
        if msg.topic != topic:
            return
        raw = base64.b64decode(msg.payload)
        obj = json.loads(AESGCM(key).decrypt(raw[4:16], raw[16:], b'DSM3'))
        with witness_lock:
            seen.add(obj['sms']['uuid'])

    witness = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id='witness-' + tag)
    witness.on_message = on_message
    witness.on_subscribe = lambda *args: subscribed.set()
    publisher = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id='pub-' + tag)
    result = {'label': a.label, 'mode': a.mode, 'tag': tag, 'count': a.count,
              'serial': a.serial, 'topic': topic, 'passed': False}
    stopped = False
    try:
        text('shell', 'am', 'force-stop', pkg)
        text('logcat', '-c')
        launch()
        wait_subscribed()
        time.sleep(3)
        result['api'] = text('shell', 'getprop', 'ro.build.version.sdk').strip()
        witness.connect(a.broker, 1883, 30)
        witness.loop_start()
        witness.subscribe(topic, qos=1)
        if not subscribed.wait(10):
            raise RuntimeError('Witness SUBACK timeout')
        publisher.connect(a.broker, 1883, 30)
        publisher.loop_start()
        payloads = []
        for i in range(a.count):
            sms = dict(uuid=tag + str(i).zfill(4), address='+8613700000%03d' % i,
                       body='F3 synthetic burst %d' % i, timestamp=int(time.time()*1000)-3600000+i,
                       type=1, status=1, deviceId=peer, simId=1, iccid=None,
                       mappingKey='ICCID_F3_' + tag, errorMsg=None)
            obj = dict(sms=sms, remarkPhone='13900000009', deviceName='F3 synthetic probe',
                       silentSync=True, historyImport=False, ts=int(time.time()*1000),
                       nonce=secrets.token_urlsafe(12))
            iv = os.urandom(12)
            raw = b'DSM3' + iv + AESGCM(key).encrypt(iv, json.dumps(obj).encode(), b'DSM3')
            payloads.append(base64.b64encode(raw))
        t0 = time.monotonic()
        infos = [publisher.publish(topic, payload, qos=1) for payload in payloads]
        result['publish_issued_seconds'] = round(time.monotonic()-t0, 3)
        log('publish_issued', **result)
        if a.mode == 'kill':
            time.sleep(max(0, a.kill_after-(time.monotonic()-t0)))
            text('shell', 'am', 'force-stop', pkg)
            stopped = True
            result['force_stop_seconds'] = round(time.monotonic()-t0, 3)
            stopped_rows = rows()
            if stopped_rows is None:
                raise RuntimeError('Stopped process DB was not stable')
            result['rows_before_relaunch'] = stopped_rows[0]
            launch()
            stopped = False
            result['relaunch_seconds'] = round(time.monotonic()-t0, 3)
            log('relaunch', **result)
        for info in infos:
            info.wait_for_publish(timeout=60)
            if not info.is_published():
                raise RuntimeError('Publisher did not receive all broker PUBACKs')
        result['broker_accepted_seconds'] = round(time.monotonic()-t0, 3)
        deadline = t0 + a.timeout
        previous = -1
        observed = (0, 0)
        while time.monotonic() < deadline:
            sample = rows()
            if sample is None:
                time.sleep(0.5)
                continue
            observed = sample
            if observed[0] != previous:
                log('room_count', rows=observed[0], elapsed=round(time.monotonic()-t0, 3))
                previous = observed[0]
            if observed == (a.count, a.count):
                result['all_in_db_seconds'] = round(time.monotonic()-t0, 3)
                break
            time.sleep(0.5)
        result['rows'], result['distinct_uuids'] = observed
        time.sleep(1)
        output = text('logcat', '-d', '-s', 'dSIM_SyncService:D', '*:S')
        (folder / 'logcat.txt').write_text(output, encoding='utf-8', newline='\n')
        result['dup_redeliveries_logged'] = sum('dup=true' in line and topic in line for line in output.splitlines())
        with witness_lock:
            result['witness_unique'] = len(seen)
        result['passed'] = observed == (a.count, a.count) and result['witness_unique'] == a.count
        log('RESULT', **result)
        if not result['passed']:
            raise AssertionError('Burst incomplete; see evidence')
    finally:
        if stopped:
            launch()
        for client in (publisher, witness):
            client.disconnect()
            client.loop_stop()
        (folder / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8', newline='\n')
        (folder / 'trace.json').write_text(json.dumps(trace, indent=2), encoding='utf-8', newline='\n')


if __name__ == '__main__':
    main()
