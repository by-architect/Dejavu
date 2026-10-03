// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/// What Dejavu remembers about the spaces collection between syncs, like on Android.
///
/// - `syncId`: The collection's sync id in meta/global. When another device resets the collection it changes, and
///   Dejavu then syncs everything again.
/// - `lastModified`: Server time of the collection when it was last synced, in decimal seconds.
/// - `keysModified`: Server time of crypto/keys when it was last read.
/// - `account`: Storage user the data belongs to.
/// - `lastSynced`: Local time of the last complete sync, in milliseconds.
/// - `server`: The server's copy of every record Dejavu applied or uploaded, tombstones included. Local data is compared
///   with it to find what to upload, and records start from it so fields Dejavu does not show are kept.
/// - `failed`: Incoming records that could not be applied yet; they are applied again on every sync.
/// - `seen`: Tabs that are not pinned, by sync id, as they were here at the end of the last sync. A tab that changed
///   since then is uploaded, and one that is gone was closed here, so it is deleted on the server.
/// - `opened`: Tabs the current sync opened, which may not show in the browser before it ends.
final class SpacesSyncData {
    var syncId: String?
    var lastModified: String?
    var keysModified: String?
    var account: String?
    var lastSynced: Int64 = 0
    var server = OrderedRecords()
    var failed = OrderedRecords()
    var seen: [String: String] = [:]
    var opened = Set<String>()

    /// Forgets the collection, so the next sync downloads everything and uploads whatever differs locally.
    func resetCollection() {
        lastModified = nil
        server.removeAll()
        failed.removeAll()
        seen.removeAll()
        opened.removeAll()
    }

    /// Forgets everything about the server, like after signing out.
    func resetAll() {
        syncId = nil
        keysModified = nil
        account = nil
        lastSynced = 0
        resetCollection()
    }
}

/// Keeps `SpacesSyncData` in a JSON file. Not thread safe; the sync engine uses it from one sync at a time.
final class SpacesSyncStore {
    private static let fileName = "spaces_sync.json"
    private static let version: Int64 = 1

    private var cached: SpacesSyncData?

    func load() -> SpacesSyncData {
        if let cached {
            return cached
        }
        let data = read()
        cached = data
        return data
    }

    func save(_ data: SpacesSyncData) {
        cached = data
        let json: [String: Any] = [
            "version": Self.version,
            "syncId": data.syncId ?? NSNull(),
            "lastModified": data.lastModified ?? NSNull(),
            "keysModified": data.keysModified ?? NSNull(),
            "account": data.account ?? NSNull(),
            "lastSynced": data.lastSynced,
            "server": data.server.values.map { $0.toCleartext() },
            "failed": data.failed.values.map { $0.toCleartext() },
            "seen": data.seen,
        ]
        DataFiles.write(Data(SyncJSON.canonical(json).utf8), to: Self.fileName)
    }

    func clear() {
        cached = SpacesSyncData()
        DataFiles.remove(Self.fileName)
    }

    private func read() -> SpacesSyncData {
        let data = SpacesSyncData()
        guard let json = DataFiles.readObject(Self.fileName), json.int64("version") == Self.version else { return data }
        data.syncId = json.string("syncId")
        data.lastModified = json.string("lastModified")
        data.keysModified = json.string("keysModified")
        data.account = json.string("account")
        data.lastSynced = json.int64("lastSynced") ?? 0
        for record in json.objects("server").compactMap({ SpacesRecord.fromCleartext($0) }) {
            data.server[record.id] = record
        }
        for record in json.objects("failed").compactMap({ SpacesRecord.fromCleartext($0) }) {
            data.failed[record.id] = record
        }
        data.seen = json.stringMap("seen")
        return data
    }
}
