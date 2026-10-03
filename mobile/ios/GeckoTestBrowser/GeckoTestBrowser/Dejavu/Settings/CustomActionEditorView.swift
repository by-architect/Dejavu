// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SwiftUI

/// A header being edited, with an id of its own so its fields keep their focus while others are added or removed.
private struct HeaderField: Identifiable {
    let id = UUID()
    var name: String
    var value: String
}

/// The field of the editor variables are inserted into.
private enum EditorField: Hashable {
    case url
    case body
    case headerName(UUID)
    case headerValue(UUID)
}

/// Adds or edits a custom action: a request sent for every tab it runs on, like a `curl` command.
struct CustomActionEditorView: View {
    let actionId: String?

    @ObservedObject private var settings = DejavuSettings.shared
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var method: HttpMethod
    @State private var url: String
    @State private var bodyText: String
    @State private var headers: [HeaderField]
    @State private var lastFocused = EditorField.url
    @State private var confirmingDelete = false
    @FocusState private var focused: EditorField?

    private static let defaultBody = "{\n  \"url\": \"${websiteurl}\",\n  \"title\": \"${websitetitle}\"\n}"
    private static let topId = "top"

    init(actionId: String?) {
        self.actionId = actionId
        let existing = DejavuSettings.shared.customActions.first { $0.id == actionId }
        _name = State(initialValue: existing?.name ?? "")
        _method = State(initialValue: existing?.method ?? .POST)
        _url = State(initialValue: existing?.url ?? "")
        _bodyText = State(initialValue: existing?.body ?? Self.defaultBody)
        let savedHeaders = existing?.headers ?? [CustomHeader(name: "Content-Type", value: "application/json")]
        _headers = State(initialValue: savedHeaders.map { HeaderField(name: $0.name, value: $0.value) })
    }

    private var existing: CustomAction? {
        settings.customActions.first { $0.id == actionId }
    }

    private var trimmedUrl: String {
        url.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var isValid: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty
            && (trimmedUrl.hasPrefix("https://") || trimmedUrl.hasPrefix("http://"))
    }

    var body: some View {
        ScrollViewReader { proxy in
            Form {
                requestSection
                headersSection
                if method.hasBody {
                    bodySection
                }
                variablesSection
                if existing != nil {
                    deleteSection
                }
                if existing == nil {
                    examplesSection(proxy)
                }
            }
            .dejavuSettingsList()
        }
        .navigationTitle(L10n.customAction)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(L10n.save) { save() }
                    .disabled(!isValid)
            }
        }
        .onChange(of: focused) { _, field in
            if let field {
                lastFocused = field
            }
        }
        .confirmationDialog(
            L10n.deleteItemTitle(name), isPresented: $confirmingDelete, titleVisibility: .visible
        ) {
            Button(L10n.delete, role: .destructive) {
                if let existing {
                    settings.deleteCustomAction(existing.id)
                }
                dismiss()
            }
            Button(L10n.cancel, role: .cancel) {}
        }
    }

    // MARK: - Sections

    private var requestSection: some View {
        Section {
            TextField(L10n.customActionName, text: $name)
                .id(Self.topId)
            Picker(L10n.customActionMethod, selection: $method) {
                ForEach(HttpMethod.allCases, id: \.self) { option in
                    Text(option.rawValue).tag(option)
                }
            }
            .pickerStyle(.segmented)
            TextField("https://example.com/save?url=${websiteurl}", text: $url, axis: .vertical)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused($focused, equals: .url)
        } header: {
            SettingsView.Header(L10n.customActionUrl)
        }
        .dejavuSettingsCard()
    }

    private var headersSection: some View {
        Section {
            ForEach($headers) { $header in
                HStack(spacing: 8) {
                    TextField(L10n.customActionHeaderName, text: $header.name)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .focused($focused, equals: .headerName(header.id))
                    TextField(L10n.customActionHeaderValue, text: $header.value)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .focused($focused, equals: .headerValue(header.id))
                    Button {
                        let id = header.id
                        headers.removeAll { $0.id == id }
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(DejavuColor.onSurfaceVariant)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(L10n.customActionRemoveHeader)
                }
            }
            Button(L10n.customActionAddHeader) {
                headers.append(HeaderField(name: "", value: ""))
            }
        } header: {
            SettingsView.Header(L10n.customActionHeaders)
        }
        .dejavuSettingsCard()
    }

    private var bodySection: some View {
        Section {
            TextEditor(text: $bodyText)
                .font(.system(.callout, design: .monospaced))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .frame(minHeight: 140)
                .focused($focused, equals: .body)
        } header: {
            SettingsView.Header(L10n.customActionBody)
        }
        .dejavuSettingsCard()
    }

    private var variablesSection: some View {
        Section {
            SettingsView.FlowLayout(spacing: 8) {
                ForEach(ActionVariable.allCases, id: \.self) { variable in
                    Button {
                        insert(variable.token)
                    } label: {
                        Text(variable.token)
                            .font(.system(.footnote, design: .monospaced))
                            .foregroundStyle(DejavuColor.onSurface)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(DejavuColor.outline))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.vertical, 4)
            Text(L10n.customActionHint)
                .font(.footnote)
                .foregroundStyle(DejavuColor.onSurfaceVariant)
        } header: {
            SettingsView.Header(L10n.customActionVariables, hint: L10n.customActionVariablesHint)
        }
        .dejavuSettingsCard()
    }

    private var deleteSection: some View {
        Section {
            Button(L10n.delete, role: .destructive) { confirmingDelete = true }
        }
        .dejavuSettingsCard()
    }

    private func examplesSection(_ proxy: ScrollViewProxy) -> some View {
        Section {
            ForEach(customActionExamples, id: \.name) { example in
                Button {
                    fill(example)
                    withAnimation { proxy.scrollTo(Self.topId, anchor: .top) }
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(example.name)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(DejavuColor.onSurface)
                        Text(example.description)
                            .font(.footnote)
                            .foregroundStyle(DejavuColor.onSurfaceVariant)
                    }
                }
            }
        } header: {
            SettingsView.Header(L10n.customActionExamples, hint: L10n.customActionExamplesHint)
        }
        .dejavuSettingsCard()
    }

    // MARK: - Editing

    /// Puts `token` at the end of the field edited last.
    private func insert(_ token: String) {
        switch lastFocused {
        case .url:
            url += token
        case .body:
            bodyText += token
        case .headerName(let id):
            if let index = headers.firstIndex(where: { $0.id == id }) {
                headers[index].name += token
            }
        case .headerValue(let id):
            if let index = headers.firstIndex(where: { $0.id == id }) {
                headers[index].value += token
            }
        }
    }

    private func fill(_ example: CustomActionExample) {
        name = example.name
        method = example.method
        url = example.url
        bodyText = example.body
        headers = example.headers.map { HeaderField(name: $0.name, value: $0.value) }
    }

    private func save() {
        let action = CustomAction(
            id: existing?.id ?? newId(),
            name: name.trimmingCharacters(in: .whitespaces),
            method: method,
            url: trimmedUrl,
            headers: headers
                .filter { !$0.name.trimmingCharacters(in: .whitespaces).isEmpty }
                .map { CustomHeader(name: $0.name.trimmingCharacters(in: .whitespaces), value: $0.value) },
            body: method.hasBody ? bodyText : "")
        settings.saveCustomAction(action)
        dismiss()
    }
}
