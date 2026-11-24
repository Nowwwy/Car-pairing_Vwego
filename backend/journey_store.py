"""Private local SQLite persistence; recommendations use counts, not model training.

Each operation owns a connection and transaction. SQLite serializes writes across
threads/processes. Retention per client: 500 choices, 100 parking, 50 meetings.
Request deduplication lasts while the corresponding choice is retained.
"""
from contextlib import contextmanager
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import re
import sqlite3
import uuid


KINDS = frozenset(('parking', 'rest', 'fuel'))


def _identifier(value, label='identifier'):
    if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', value):
        raise ValueError(f'invalid {label}')
    return value


def _text(value, limit, label, required=False):
    if not isinstance(value, str) or len(value) > limit or '\x00' in value or (required and not value.strip()):
        raise ValueError(f'invalid {label}')
    return value.strip()


def _number(value, low, high, label):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not low <= value <= high:
        raise ValueError(f'invalid {label}')
    return float(value)


def _place(value):
    if not isinstance(value, dict):
        raise ValueError('place must be an object')
    result = {'name': _text(value.get('name', ''), 200, 'place name', True),
              'latitude': _number(value.get('latitude'), -90, 90, 'latitude'),
              'longitude': _number(value.get('longitude'), -180, 180, 'longitude')}
    if 'address' in value:
        result['address'] = _text(value['address'], 500, 'address')
    if 'id' in value:
        result['id'] = _text(value['id'], 200, 'place id', True)
    return result


def _kind(value):
    if not isinstance(value, str) or value not in KINDS:
        raise ValueError('invalid place kind')
    return value


def _key(place):
    # Provider IDs are not universally present; actual coordinates identify a stop.
    return f"{place['latitude']:.5f},{place['longitude']:.5f}"


def _json(value):
    try:
        return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(',', ':'))
    except (TypeError, ValueError) as exc:
        raise ValueError('value must be finite JSON') from exc


def _now():
    return datetime.now(timezone.utc).isoformat()


class JourneyStore:
    def __init__(self, path):
        self.path = str(path)
        if self.path == ':memory:':
            raise ValueError('JourneyStore requires a durable file path')
        Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        with self._db() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS rooms(code TEXT PRIMARY KEY, snapshot TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS profiles(client TEXT PRIMARY KEY, learning INTEGER NOT NULL DEFAULT 0, plate TEXT NOT NULL DEFAULT '');
                CREATE TABLE IF NOT EXISTS choices(
                    id INTEGER PRIMARY KEY AUTOINCREMENT, client TEXT NOT NULL,
                    kind TEXT NOT NULL, place_key TEXT NOT NULL, accepted INTEGER NOT NULL,
                    dwell REAL NOT NULL, created_at TEXT NOT NULL, request_id TEXT,
                    UNIQUE(client,request_id));
                CREATE INDEX IF NOT EXISTS choice_client ON choices(client,kind);
                CREATE TABLE IF NOT EXISTS records(
                    seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE,
                    client TEXT NOT NULL, kind TEXT NOT NULL, data TEXT NOT NULL);
                CREATE INDEX IF NOT EXISTS record_client ON records(client,kind,seq);
            ''')

    @contextmanager
    def _db(self):
        db = sqlite3.connect(self.path, timeout=30)
        db.row_factory = sqlite3.Row
        try:
            db.execute('BEGIN IMMEDIATE')
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    @staticmethod
    def _ensure_profile(db, client):
        db.execute('INSERT OR IGNORE INTO profiles(client) VALUES(?)', (client,))

    @staticmethod
    def _profile(db, client):
        row = db.execute('SELECT learning,plate FROM profiles WHERE client=?', (client,)).fetchone()
        count = db.execute('SELECT COUNT(*) FROM choices WHERE client=?', (client,)).fetchone()[0]
        return {'learning_enabled': bool(row['learning']) if row else False,
                'plate': row['plate'] if row else '', 'choices_count': count}

    def save_room(self, code, snapshot):
        if not isinstance(code, str) or not re.fullmatch(r'[0-9]{6}', code):
            raise ValueError('invalid room code')
        if not isinstance(snapshot, dict):
            raise ValueError('snapshot must be an object')
        data = _json(snapshot)
        if len(data.encode('utf-8')) > 2_000_000:
            raise ValueError('snapshot too large')
        with self._db() as db:
            db.execute('INSERT OR REPLACE INTO rooms VALUES(?,?)', (code, data))

    def load_rooms(self):
        with self._db() as db:
            return {row['code']: json.loads(row['snapshot']) for row in db.execute('SELECT * FROM rooms ORDER BY code')}

    def delete_room(self, code):
        with self._db() as db:
            db.execute('DELETE FROM rooms WHERE code=?', (code,))

    def get_profile(self, client_id):
        client_id = _identifier(client_id, 'client id')
        with self._db() as db:
            return self._profile(db, client_id)

    def update_profile(self, client_id, learning_enabled=None, plate=None):
        client_id = _identifier(client_id, 'client id')
        if learning_enabled is not None and not isinstance(learning_enabled, bool):
            raise ValueError('learning_enabled must be boolean')
        if plate is not None:
            plate = _text(plate, 24, 'plate')
        with self._db() as db:
            self._ensure_profile(db, client_id)
            if learning_enabled is not None:
                db.execute('UPDATE profiles SET learning=? WHERE client=?', (int(learning_enabled), client_id))
            if plate is not None:
                db.execute('UPDATE profiles SET plate=? WHERE client=?', (plate, client_id))
            return self._profile(db, client_id)

    def record_choice(self, client_id, kind, place, accepted, dwell_minutes=0, request_id=None):
        client_id = _identifier(client_id, 'client id')
        kind, place = _kind(kind), _place(place)
        if not isinstance(accepted, bool):
            raise ValueError('accepted must be boolean')
        dwell = _number(dwell_minutes, 0, 1440, 'dwell_minutes')
        if request_id is not None:
            request_id = _identifier(request_id, 'request id')
        with self._db() as db:
            if not self._profile(db, client_id)['learning_enabled']:
                return False
            cursor = db.execute('INSERT OR IGNORE INTO choices(client,kind,place_key,accepted,dwell,created_at,request_id) VALUES(?,?,?,?,?,?,?)',
                                (client_id, kind, _key(place), int(accepted), dwell, _now(), request_id))
            recorded = cursor.rowcount == 1
            db.execute('DELETE FROM choices WHERE client=? AND id NOT IN (SELECT id FROM choices WHERE client=? ORDER BY id DESC LIMIT 500)', (client_id, client_id))
            return recorded

    def rank_places(self, client_id, kind, places, origin=None):
        client_id, kind = _identifier(client_id, 'client id'), _kind(kind)
        normalized = [_place(place) for place in places]
        with self._db() as db:
            enabled = self._profile(db, client_id)['learning_enabled']
            rows = db.execute('SELECT place_key,accepted,created_at FROM choices WHERE client=? AND kind=? ORDER BY id', (client_id, kind)).fetchall() if enabled else []
        stats = {}
        for row in rows:
            item = stats.setdefault(row['place_key'], [0, 0, None])
            item[0 if row['accepted'] else 1] += 1
            if row['accepted']:
                item[2] = row['created_at']
        now = datetime.now(timezone.utc)
        ranked = []
        for index, (original, place) in enumerate(zip(places, normalized)):
            accepted, rejected, recent = stats.get(_key(place), [0, 0, None])
            recency = 0 if recent is None else 1 / (1 + max(0, (now - datetime.fromisoformat(recent)).total_seconds()) / 86400)
            score = accepted * 3 - rejected * 4 + recency
            annotation = f'내 선택 {accepted}회 · 거절 {rejected}회 (빈도·최근 선택 기준)' if enabled else '선택 기록 추천 꺼짐'
            ranked.append((score, index, dict(original, recommendation=annotation, used_count=accepted)))
        return [item[2] for item in sorted(ranked, key=lambda item: (-item[0], item[1]))]

    def _save_record(self, client_id, kind, payload, cap):
        client_id = _identifier(client_id, 'client id')
        record = dict(payload, id=uuid.uuid4().hex, created_at=_now())
        with self._db() as db:
            db.execute('INSERT INTO records(id,client,kind,data) VALUES(?,?,?,?)', (record['id'], client_id, kind, _json(record)))
            db.execute('DELETE FROM records WHERE client=? AND kind=? AND seq NOT IN (SELECT seq FROM records WHERE client=? AND kind=? ORDER BY seq DESC LIMIT ?)', (client_id, kind, client_id, kind, cap))
        return record

    def save_parking(self, client_id, record):
        if not isinstance(record, dict):
            raise ValueError('parking record must be an object')
        payload = {field: _text(record.get(field, ''), limit, field) for field, limit in [('floor', 24), ('zone', 40), ('note', 120)]}
        payload['place'] = _place(record['place']) if record.get('place') is not None else None
        return self._save_record(client_id, 'parking', payload, 100)

    def save_meeting(self, client_id, name, place):
        return self._save_record(client_id, 'meeting', {'name': _text(name, 100, 'meeting name', True), 'place': _place(place)}, 50)

    def _list_records(self, client_id, kind):
        client_id = _identifier(client_id, 'client id')
        with self._db() as db:
            return [json.loads(row['data']) for row in db.execute('SELECT data FROM records WHERE client=? AND kind=? ORDER BY seq DESC', (client_id, kind))]

    def list_parking(self, client_id):
        return self._list_records(client_id, 'parking')

    def list_meetings(self, client_id):
        return self._list_records(client_id, 'meeting')

    def _delete_record(self, client_id, kind, record_id):
        client_id, record_id = _identifier(client_id, 'client id'), _identifier(record_id, 'record id')
        with self._db() as db:
            return db.execute('DELETE FROM records WHERE client=? AND kind=? AND id=?', (client_id, kind, record_id)).rowcount == 1

    def delete_parking(self, client_id, record_id):
        return self._delete_record(client_id, 'parking', record_id)

    def delete_meeting(self, client_id, record_id):
        return self._delete_record(client_id, 'meeting', record_id)

    def clear_history(self, client_id):
        client_id = _identifier(client_id, 'client id')
        with self._db() as db:
            db.execute('DELETE FROM choices WHERE client=?', (client_id,))
