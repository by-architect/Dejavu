// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/// Dejavu's data as the sync engine reads and changes it. Everything runs on the main thread, where the workspaces,
/// containers and tabs live.
protocol SpacesLocalData: AnyObject {
    /// Name of the workspace a fresh Dejavu starts with.
    @MainActor var defaultWorkspaceName: String { get }

    /// Current workspaces, containers and tabs.
    @MainActor func read() -> LocalSpaces

    /// Applies `transform` to the workspaces as one change and returns its second value.
    @MainActor func update<T>(_ transform: (WorkspaceState) -> (WorkspaceState, T)) -> T

    /// Creates container `contextId`, or updates its name, color and icon.
    @MainActor func saveContainer(contextId: String, name: String, color: ContainerColor, icon: ContainerIcon)

    /// Deletes container `contextId`; its tabs move out of it.
    @MainActor func removeContainer(_ contextId: String)

    @MainActor func builtinName(_ container: BuiltinContainer) -> String

    /// Opens, points elsewhere or closes open tabs as applied records ask.
    @MainActor func runTabOps(_ ops: [TabOp])
}

/// How a sync ended when it did not throw.
enum SpacesSyncResult {
    /// Synced; counts of records received, sent and still waiting to apply.
    case synced(received: Int, sent: Int, pending: Int)

    /// Firefox Sync has not set up this account's storage yet; Firefox's own sync does it.
    case notSetUp

    /// Spaces sync is not turned on for the account: no device set up the spaces collection, or one turned it off.
    /// Syncing with `turnOn` turns it on, which also turns it on in Zen.
    case notTurnedOn

    /// Another device uses a newer version of the spaces collection.
    case needsUpdate(serverVersion: Int)
}

/// Syncs Dejavu's workspaces with the "spaces" collection of Firefox Sync, which Zen's Spaces sync engine
/// (ZenSpacesSync.sys.mjs) reads and writes, so Dejavu and Zen share spaces, their looks, containers, pinned tabs,
/// essentials, folders and their order. It is a port of the Android app's engine and keeps the same records.
///
/// Like Zen, it never tracks individual changes. It compares what Dejavu's data maps to (`SpacesProjector`) with the
/// server's copy of each record, and uploads the difference. Incoming records always apply first, and a record Dejavu
/// applied maps back to itself, so a sync never sends back what it just received.
///
/// One call at a time: `DejavuSync` runs every call of the engine one after the other.
final class SpacesSyncEngine {
    /// Name of the collection, and of Zen's sync engine in meta/global.
    static let collection = "spaces"

    /// Version of the records, matching Zen's `ZenSpacesSyncEngine.version`.
    static let engineVersion = 3

    private static let storageVersion = 5
    private static let pageSize = 1000
    private static let wipeDeletions = 2
    private static let syncIdBytes = 9
    private static let urlQueryAllowed = CharacterSet(
        charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._*")

    private let http: SyncHttp
    private let store: SpacesSyncStore
    private let local: SpacesLocalData
    private let clock: SyncClock
    private let log: (String) -> Void
    private var token: SyncToken?

    /// Time before which the servers asked not to sync again, in local milliseconds.
    private(set) var backoffUntil: Int64 = 0

    init(
        http: SyncHttp, store: SpacesSyncStore, local: SpacesLocalData, clock: SyncClock = SyncClock(),
        log: @escaping (String) -> Void = { _ in }
    ) {
        self.http = http
        self.store = store
        self.local = local
        self.clock = clock
        self.log = log
    }

    /// Forgets everything about the server, for a sign out.
    func reset() {
        token = nil
        store.clear()
    }

    /// Time of the last complete sync in milliseconds, or 0.
    var lastSynced: Int64 {
        store.load().lastSynced
    }

    /// Whether local data differs from the server's copy, without touching the network.
    func hasLocalChanges() async -> Bool {
        let data = store.load()
        guard data.syncId != nil else { return false }
        let current = await local.read()
        let projection = SpacesProjector(local: current, server: data.server, seen: data.seen).project()
        return !projection.changes(against: data.server).isEmpty
    }

    /// Syncs. When spaces sync is not turned on for the account, it only turns it on with `turnOn`, since that turns it
    /// on for every device of the account, Zen included.
    func sync(_ auth: SyncAuth, turnOn: Bool = false) async throws -> SpacesSyncResult {
        do {
            return try await run(auth, turnOn: turnOn, allowWipeCheck: true)
        } catch let error as SyncAuthError {
            // Tokens expire before their stated end sometimes; one retry with a new one is enough.
            token = nil
            log("Token refused, retrying once: \(error)")
            return try await run(auth, turnOn: turnOn, allowWipeCheck: true)
        }
    }

    private func run(_ auth: SyncAuth, turnOn: Bool, allowWipeCheck: Bool) async throws -> SpacesSyncResult {
        let syncKeys = try KeyBundle.fromSyncKey(auth.syncKey)
        let token = try await currentToken(auth)
        let clock = self.clock
        let client = StorageClient(http: http, token: token, clock: clock) { [weak self] seconds in
            self?.backoffUntil = clock.millis() + seconds * 1000
        }
        let data = store.load()
        if data.account != token.uid {
            log("New storage user, forgetting the previous one")
            data.resetAll()
            data.account = token.uid
        }

        let infoResponse = try await client.expect("GET", "info/collections")
        guard let info = SyncJSON.parseObject(infoResponse.body) else {
            throw SyncServerError(status: infoResponse.status, message: "Malformed info/collections")
        }
        if info["meta"] == nil || info["crypto"] == nil {
            return .notSetUp
        }
        let configuration = try await client.request("GET", "info/configuration")
        let limits = UploadLimits(configuration.isSuccess ? SyncJSON.parseObject(configuration.body) : nil)

        if let result = try await checkMetaGlobal(client, data, turnOn: turnOn) {
            return result
        }
        guard let keys = try await collectionKeys(client, syncKeys, data) else { return .notSetUp }

        let serverModified = timestampOf(info[Self.collection])
        if let last = data.lastModified, serverModified == nil || isNewerTimestamp(last, than: serverModified) {
            log("The spaces collection was emptied on the server, syncing everything again")
            data.resetCollection()
        }
        var collectionModified = data.lastModified
        var incoming = OrderedRecords()
        if let serverModified, data.lastModified == nil || isNewerTimestamp(serverModified, than: data.lastModified) {
            let downloaded = try await download(client, keys, since: data.lastModified)
            incoming = downloaded.records
            collectionModified = downloaded.modified ?? serverModified
        }
        log("Downloaded \(incoming.count) records")

        var batch = OrderedRecords()
        for record in data.failed.values {
            batch[record.id] = record
        }
        let beforeApply = await local.read()
        for record in unopenedTabs(beforeApply, data) {
            batch[record.id] = record
        }
        for record in incoming.values {
            batch[record.id] = record
        }
        if !batch.isEmpty {
            await applyIncoming(batch.values, data)
        }
        data.lastModified = collectionModified
        store.save(data)

        let current = await local.read()
        let changes = SpacesProjector(local: current, server: data.server, seen: data.seen).project()
            .changes(against: data.server)
        if allowWipeCheck && looksWiped(current, changes, data) {
            // Data that looks freshly reset must not delete everything other devices have; take it back instead.
            log("Local data looks reset, downloading everything again instead of deleting it on the server")
            data.resetCollection()
            store.save(data)
            return try await run(auth, turnOn: false, allowWipeCheck: false)
        }

        var sent = 0
        if !changes.isEmpty {
            log("Uploading \(changes.count) records: " + changes.map { $0.deleted ? "-\($0.id)" : $0.id }.joined(separator: ", "))
            let uploaded = try await upload(client, keys, changes, since: collectionModified, limits: limits)
            for change in changes where uploaded.succeeded.contains(change.id) {
                data.server[change.id] = change
            }
            sent = uploaded.succeeded.count
            data.lastModified = uploaded.lastModified ?? data.lastModified
        }
        rememberTabs(current, data)
        data.lastSynced = clock.millis()
        store.save(data)
        return .synced(received: incoming.count, sent: sent, pending: data.failed.count)
    }

    /// The server's tabs that are not pinned and were never open here, like Zen's unpinned tabs from before they were
    /// synced here too. Applying them again opens them, where the projection would otherwise leave them out.
    private func unopenedTabs(_ current: LocalSpaces, _ data: SpacesSyncData) -> [SpacesRecord] {
        guard current.normalTabs, let tabs = current.tabs else { return [] }
        var here = Set(tabs.map { current.state.syncIdOf($0.id) })
        here.formUnion(current.state.pins.map { $0.id })
        return data.server.values.filter { $0.isNormalTab && !here.contains($0.id) && data.seen[$0.id] == nil }
    }

    /// Remembers the tabs that are not pinned as they are now, to find later which of them changed or closed here.
    private func rememberTabs(_ current: LocalSpaces, _ data: SpacesSyncData) {
        guard current.normalTabs, let tabs = current.tabs else { return }
        let pinned = Set(current.state.pins.compactMap { $0.tabId })
        var now: [String: String] = [:]
        for tab in tabs where !pinned.contains(tab.id) {
            now[current.state.syncIdOf(tab.id)] = tab.fingerprint
        }
        // Tabs this sync opened may not show in the browser yet; they count as seen so closing them deletes them.
        let opening = data.seen.filter { id, _ in
            now[id] == nil && data.server[id]?.isNormalTab == true && data.opened.contains(id)
        }
        data.seen = now.merging(opening) { current, _ in current }
        data.opened.removeAll()
    }

    private func currentToken(_ auth: SyncAuth) async throws -> SyncToken {
        if let token, token.expiresAt > clock.millis() {
            return token
        }
        let fresh = try await TokenServerClient(http: http, clock: clock).fetch(auth)
        token = fresh
        return fresh
    }

    /// Checks the collection's entry in meta/global, like Firefox's `SyncEngine._syncStartup`. A newer version stops the
    /// sync, and a changed sync id means the collection was reset elsewhere, so everything is synced again. A missing,
    /// declined or older entry means spaces sync is off for the account; with `turnOn` it is replaced by this version
    /// with a new sync id, which other devices, Zen included, then follow.
    private func checkMetaGlobal(
        _ client: StorageClient, _ data: SpacesSyncData, turnOn: Bool
    ) async throws -> SpacesSyncResult? {
        let response = try await client.expect("GET", "storage/meta/global", allowed: [404])
        if response.status == 404 {
            return .notSetUp
        }
        let modified = response.header("X-Last-Modified")
        guard var payload = SyncJSON.parseObject(SyncJSON.parseObject(response.body)?.string("payload")) else {
            throw SyncServerError(status: response.status, message: "Malformed meta/global")
        }
        if jsonInt(payload["storageVersion"]) != Self.storageVersion {
            return .notSetUp
        }

        var engines = payload.object("engines") ?? [:]
        let entry = engines.object(Self.collection)
        let version = entry.map { jsonInt($0["version"]) } ?? 0
        let syncId = entry?.string("syncID")
        if version > Self.engineVersion {
            return .needsUpdate(serverVersion: version)
        }
        if version == Self.engineVersion, let syncId {
            if syncId != data.syncId {
                log("The spaces collection was set up or reset elsewhere, syncing everything")
                data.syncId = syncId
                data.resetCollection()
            }
            return nil
        }
        if !turnOn {
            return .notTurnedOn
        }

        let newSyncId = Self.newSyncId()
        log("Turning spaces sync on in meta/global (was version \(version))")
        engines[Self.collection] = ["version": Self.engineVersion, "syncID": newSyncId] as [String: Any]
        payload["engines"] = engines
        if let declined = payload.strings("declined") {
            payload["declined"] = declined.filter { $0 != Self.collection }
        }
        let body: [String: Any] = ["id": "global", "payload": SyncJSON.canonical(payload)]
        _ = try await client.expect(
            "PUT", "storage/meta/global", body: SyncJSON.canonical(body), ifUnmodifiedSince: modified)
        data.syncId = newSyncId
        data.resetCollection()
        return nil
    }

    /// The keys of the spaces collection, read from crypto/keys with the account's sync key.
    private func collectionKeys(
        _ client: StorageClient, _ syncKeys: KeyBundle, _ data: SpacesSyncData
    ) async throws -> KeyBundle? {
        let response = try await client.expect("GET", "storage/crypto/keys", allowed: [404])
        if response.status == 404 {
            return nil
        }
        guard let payload = SyncJSON.parseObject(SyncJSON.parseObject(response.body)?.string("payload")) else {
            throw SyncServerError(status: response.status, message: "Malformed crypto/keys")
        }
        guard let keys = SyncJSON.parseObject(try syncKeys.decrypt(payload)) else {
            throw SyncCryptoError("Malformed crypto/keys")
        }
        guard
            let bundle = KeyBundle.fromPair(keys.object("collections")?[Self.collection] as? [Any])
                ?? KeyBundle.fromPair(keys["default"] as? [Any])
        else {
            throw SyncCryptoError("crypto/keys has no default key")
        }
        let modified = response.header("X-Last-Modified")
        if data.keysModified != nil && modified != data.keysModified {
            log("The collection keys changed, syncing everything again")
            data.resetCollection()
        }
        data.keysModified = modified
        return bundle
    }

    /// Downloads the records changed since `since`, and the collection's server time.
    private func download(
        _ client: StorageClient, _ keys: KeyBundle, since: String?
    ) async throws -> (records: OrderedRecords, modified: String?) {
        var records = OrderedRecords()
        var modified: String?
        var offset: String?
        var skipped = 0
        repeat {
            var query = "full=1&sort=oldest&limit=\(Self.pageSize)"
            if let since {
                query += "&newer=\(since)"
            }
            if let offset {
                query += "&offset=\(Self.encode(offset))"
            }
            let response = try await client.expect(
                "GET", "storage/\(Self.collection)?\(query)",
                ifUnmodifiedSince: offset != nil ? modified : nil, allowed: [404])
            if response.status == 404 {
                break
            }
            if modified == nil {
                modified = response.header("X-Last-Modified")
            }
            guard let items = SyncJSON.parseArray(response.body) else {
                throw SyncServerError(status: response.status, message: "Malformed records")
            }
            for item in items {
                guard let bso = item as? [String: Any], let id = bso.string("id") else { continue }
                if let record = decode(bso, id: id, keys: keys) {
                    records[id] = record
                } else {
                    skipped += 1
                }
            }
            offset = response.header("X-Weave-Next-Offset")
        } while offset != nil
        if skipped > 0 {
            log("Skipped \(skipped) records that could not be read")
        }
        return (records, modified)
    }

    private func decode(_ bso: [String: Any], id: String, keys: KeyBundle) -> SpacesRecord? {
        guard let payload = SyncJSON.parseObject(bso.string("payload")),
            let cleartext = SyncJSON.parseObject(try? keys.decrypt(payload))
        else {
            return nil
        }
        if let cleartextId = cleartext.string("id"), cleartextId != id {
            return nil
        }
        return SpacesRecord.fromCleartext(id, cleartext)
    }

    /// Applies `records`: containers first, including Firefox's default containers the records use, then everything
    /// else, then deleted containers once nothing relies on them.
    private func applyIncoming(_ records: [SpacesRecord], _ data: SpacesSyncData) async {
        let before = await local.read()
        let localContainers = Dictionary(
            before.containers.filter { !$0.temporary }.map { ($0.contextId, $0) }, uniquingKeysWith: { _, last in last })
        var containerRecords: [SpacesRecord] = []
        var others: [SpacesRecord] = []
        for record in records {
            let isContainer = record.kind == RecordKind.container
                || (record.deleted
                    && (localContainers[record.id] != nil || data.server[record.id]?.kind == RecordKind.container))
            if isContainer {
                containerRecords.append(record)
            } else {
                others.append(record)
            }
        }
        var applied: [SpacesRecord] = []
        var failed: [SpacesRecord] = []

        for record in containerRecords where !record.deleted {
            await saveContainer(record, existing: localContainers[record.id])
            applied.append(record)
        }
        await addBuiltinContainers(others)
        let afterContainers = await local.read()
        let containerIds = Set(afterContainers.containers.filter { !$0.temporary }.map { $0.contextId })

        let defaultName = await local.defaultWorkspaceName
        let applier = SpacesApplier(
            server: data.server,
            containerIds: containerIds,
            firstSync: data.server.isEmpty,
            defaultName: defaultName,
            now: clock.millis(),
            normalTabs: before.normalTabs,
            tabs: before.tabs.map { tabs in
                Dictionary(tabs.map { ($0.id, $0) }, uniquingKeysWith: { _, last in last })
            })
        let incoming = others
        let result = await local.update { state -> (WorkspaceState, ApplyResult) in
            let outcome = applier.apply(state, incoming)
            return (outcome.state, outcome)
        }
        applied += result.applied
        failed += result.failed
        if !result.tabOps.isEmpty {
            await local.runTabOps(result.tabOps)
            for op in result.tabOps {
                if case .open(let id, let url, let title, let contextId, _) = op {
                    data.seen[id] = LocalTab(id: id, url: url, title: title, contextId: contextId, awake: false)
                        .fingerprint
                    data.opened.insert(id)
                }
            }
        }

        for record in containerRecords where record.deleted {
            if containerIds.contains(record.id) {
                await local.removeContainer(record.id)
            }
            applied.append(record)
        }

        let appliedIds = Set(applied.map { $0.id })
        let failedIds = Set(failed.map { $0.id })
        for record in applied {
            data.server[record.id] = record
            data.failed.remove(record.id)
        }
        for record in failed {
            data.failed[record.id] = record
        }
        for record in records where !appliedIds.contains(record.id) && !failedIds.contains(record.id) {
            data.failed.remove(record.id)
        }
        log("Applied \(applied.count) records, \(failed.count) waiting for records they need")
    }

    /// Creates or updates the container of an incoming container `record`; Zen skips containers without a name.
    private func saveContainer(_ record: SpacesRecord, existing: ContainerRecord?) async {
        guard let fields = record.data, let name = fields.string("name"), !name.isEmpty else { return }
        let color = containerColorOf(fields["color"])
        let icon = containerIconOf(fields["icon"])
        if existing?.name != name || existing?.color != color || existing?.icon != icon {
            await local.saveContainer(contextId: record.id, name: name, color: color, icon: icon)
        }
    }

    /// Creates the Firefox default containers that `records` use and Dejavu does not have yet.
    private func addBuiltinContainers(_ records: [SpacesRecord]) async {
        let current = await local.read()
        let present = Set(current.containers.map { $0.contextId })
        var added = Set<String>()
        for record in records {
            guard let guid = record.data?.string("containerGuid"), !present.contains(guid),
                let builtin = BuiltinContainer.of(guid), added.insert(guid).inserted
            else {
                continue
            }
            let name = await local.builtinName(builtin)
            await local.saveContainer(contextId: builtin.guid, name: name, color: builtin.color, icon: builtin.icon)
        }
    }

    /// Whether local data looks like it was lost rather than deleted: nothing but a new default workspace is left, while
    /// the server has spaces and pins Dejavu had synced.
    private func looksWiped(_ current: LocalSpaces, _ changes: [SpacesRecord], _ data: SpacesSyncData) -> Bool {
        let kinds: Set<String> = [RecordKind.space, RecordKind.tab, RecordKind.folder]
        let deletions = changes.filter { change in
            guard change.deleted, let known = data.server[change.id], !known.isNormalTab else { return false }
            return known.kind.map { kinds.contains($0) } ?? false
        }.count
        guard current.state.workspaces.count == 1 else { return false }
        let workspace = current.state.workspaces[0]
        return deletions >= Self.wipeDeletions && current.state.pins.isEmpty && data.server[workspace.id] == nil
    }

    private struct Uploaded {
        let succeeded: Set<String>
        let lastModified: String?
    }

    /// One record ready to upload, as the JSON of its BSO.
    private struct Outgoing {
        let id: String
        let json: String
    }

    /// Uploads `records` in as few requests as the server allows. Several requests use the server's batch mode, so other
    /// devices see all of them or none.
    private func upload(
        _ client: StorageClient, _ keys: KeyBundle, _ records: [SpacesRecord], since: String?, limits: UploadLimits
    ) async throws -> Uploaded {
        let chunks = Self.chunks(try encrypt(keys, records, limits: limits), limits: limits)
        var succeeded = Set<String>()
        var lastModified: String?
        var ifUnmodifiedSince = since
        var batchId: String?
        var batched = chunks.count > 1
        for (index, chunk) in chunks.enumerated() {
            let body = "[" + chunk.map { $0.json }.joined(separator: ",") + "]"
            let path: String
            if !batched {
                path = "storage/\(Self.collection)"
            } else if let batchId {
                path = "storage/\(Self.collection)?batch=\(Self.encode(batchId))"
                    + (index == chunks.count - 1 ? "&commit=true" : "")
            } else {
                path = "storage/\(Self.collection)?batch=true"
            }
            let response = try await client.expect("POST", path, body: body, ifUnmodifiedSince: ifUnmodifiedSince)
            let result = SyncJSON.parseObject(response.body) ?? [:]
            succeeded.formUnion(result.strings("success") ?? [])
            for id in (result.object("failed") ?? [:]).keys {
                log("Server refused \(id)")
            }
            if batched && response.status == 202 {
                if batchId == nil {
                    batchId = result.string("batch") ?? (result["batch"] as? NSNumber)?.stringValue
                }
            } else {
                // Committed, or a server without batches: each request is applied on its own.
                batched = false
                lastModified = response.header("X-Last-Modified") ?? timestampOf(result["modified"])
                ifUnmodifiedSince = lastModified
            }
        }
        return Uploaded(succeeded: succeeded, lastModified: lastModified)
    }

    /// The upload JSON of each record, without records too large for the server.
    private func encrypt(_ keys: KeyBundle, _ records: [SpacesRecord], limits: UploadLimits) throws -> [Outgoing] {
        try records.compactMap { record in
            let payload = SyncJSON.canonical(try keys.encrypt(SyncJSON.canonical(record.toCleartext())))
            if payload.utf8.count > limits.maxRecordPayloadBytes {
                log("Record \(record.id) is too large to upload")
                return nil
            }
            return Outgoing(id: record.id, json: SyncJSON.canonical(["id": record.id, "payload": payload]))
        }
    }

    /// Splits `items` into requests within the server's limits.
    private static func chunks(_ items: [Outgoing], limits: UploadLimits) -> [[Outgoing]] {
        var chunks: [[Outgoing]] = []
        var bytes = 0
        for item in items {
            let size = item.json.utf8.count
            if let last = chunks.last, last.count < limits.maxPostRecords, bytes + size <= limits.maxPostBytes {
                chunks[chunks.count - 1].append(item)
                bytes += size
            } else {
                chunks.append([item])
                bytes = size
            }
        }
        return chunks
    }

    /// Upload limits from info/configuration.
    private struct UploadLimits {
        private static let defaultPostRecords = 100
        private static let defaultPostBytes = 2 * 1024 * 1024
        private static let defaultRecordBytes = 256 * 1024

        let maxPostRecords: Int
        let maxPostBytes: Int
        let maxRecordPayloadBytes: Int

        init(_ config: [String: Any]?) {
            maxPostRecords = config?.int("max_post_records") ?? Self.defaultPostRecords
            maxPostBytes = min(
                config?.int("max_post_bytes") ?? Self.defaultPostBytes,
                config?.int("max_request_bytes") ?? Self.defaultPostBytes)
            maxRecordPayloadBytes = config?.int("max_record_payload_bytes") ?? Self.defaultRecordBytes
        }
    }

    /// A 12 character sync id, like Firefox's `Utils.makeGUID`.
    static func newSyncId() -> String {
        secureRandomBytes(syncIdBytes).base64URLEncoded
    }

    private static func encode(_ text: String) -> String {
        text.addingPercentEncoding(withAllowedCharacters: urlQueryAllowed) ?? text
    }
}
