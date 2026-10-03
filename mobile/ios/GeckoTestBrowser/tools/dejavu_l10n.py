#!/usr/bin/env python3
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Makes the iOS app's translations from the Android app's.

Reads Dejavu's strings in every language from the Android app (res/values*/dejavu_strings.xml) and writes:

- GeckoTestBrowser/Resources/Localizable.xcstrings, the String Catalog Xcode compiles into the app, and
- GeckoTestBrowser/Dejavu/Support/L10n+Generated.swift, one Swift accessor per string, like `L10n.newTab`.

Run it again after the Android strings change:

    python3 mobile/ios/GeckoTestBrowser/tools/dejavu_l10n.py
"""

import json
import keyword
import re
import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
APP = TOOLS.parent / "GeckoTestBrowser"
TREE = TOOLS.parents[3]
ANDROID_RES = TREE / "mobile/android/fenix/app/src/main/res"
CATALOG = APP / "Resources/Localizable.xcstrings"
SWIFT = APP / "Dejavu/Support/L10n+Generated.swift"
PREFIX = "dejavu_"

# Android resource folders whose names are not the language code iOS uses.
LOCALE_NAMES = {
    "in": "id",
    "iw": "he",
    "es-rES": "es-ES",
    "fy-rNL": "fy",
    "ga-rIE": "ga",
    "gu-rIN": "gu",
    "hi-rIN": "hi",
    "hy-rAM": "hy",
    "nb-rNO": "nb",
    "ne-rNP": "ne",
    "nn-rNO": "nn",
    "pa-rIN": "pa",
    "sv-rSE": "sv",
    "zh-rCN": "zh-Hans",
    "zh-rTW": "zh-Hant",
}

SWIFT_KEYWORDS = {
    "associatedtype", "class", "deinit", "enum", "extension", "fileprivate", "func", "import", "init", "inout",
    "internal", "let", "open", "operator", "private", "protocol", "public", "rethrows", "static", "struct",
    "subscript", "typealias", "var", "break", "case", "continue", "default", "defer", "do", "else", "fallthrough",
    "for", "guard", "if", "in", "repeat", "return", "switch", "where", "while", "as", "catch", "false", "is", "nil",
    "super", "self", "Self", "throw", "throws", "true", "try",
}

FORMAT = re.compile(r"%(?:(\d+)\$)?([sd])")


def locale_of(folder):
    """The iOS language code of an Android resource folder like values-pt-rBR, or None for values."""
    if folder == "values":
        return None
    name = folder[len("values-"):]
    if name in LOCALE_NAMES:
        return LOCALE_NAMES[name]
    return re.sub(r"-r([A-Z]{2})$", r"-\1", name)


def unescape(text):
    """The text of an Android string resource, as the app shows it."""
    text = text.strip()
    if len(text) >= 2 and text.startswith('"') and text.endswith('"'):
        text = text[1:-1]
    result = []
    index = 0
    while index < len(text):
        char = text[index]
        if char == "\\" and index + 1 < len(text):
            following = text[index + 1]
            if following == "n":
                result.append("\n")
            elif following == "t":
                result.append("\t")
            elif following == "u" and index + 5 < len(text):
                result.append(chr(int(text[index + 2:index + 6], 16)))
                index += 4
            else:
                result.append(following)
            index += 2
            continue
        result.append(char)
        index += 1
    return "".join(result)


def to_ios_format(text):
    """Android's %1$s and %1$d placeholders as iOS writes them, %1$@ and %1$lld."""

    def replace(match):
        position = f"{match.group(1)}$" if match.group(1) else ""
        return f"%{position}@" if match.group(2) == "s" else f"%{position}lld"

    return FORMAT.sub(replace, text)


def arguments_of(text):
    """The types of the placeholders of an Android string, in argument order: 's' or 'd'."""
    found = {}
    next_position = 1
    for match in FORMAT.finditer(text):
        if match.group(1):
            position = int(match.group(1))
        else:
            position = next_position
            next_position += 1
        found[position] = match.group(2)
    return [found[position] for position in sorted(found)]


def read(path):
    """The strings and plurals of one dejavu_strings.xml: {key: str} and {key: {quantity: str}}."""
    root = ElementTree.parse(path).getroot()
    strings = {}
    plurals = {}
    for element in root:
        name = element.get("name")
        if name is None or element.get("translatable") == "false" or not name.startswith(PREFIX):
            continue
        if element.tag == "string":
            strings[name] = unescape("".join(element.itertext()))
        elif element.tag == "plurals":
            plurals[name] = {
                item.get("quantity"): unescape("".join(item.itertext())) for item in element if item.tag == "item"
            }
    return strings, plurals


def accessor_name(key):
    parts = key[len(PREFIX):].split("_")
    name = parts[0] + "".join(part[:1].upper() + part[1:] for part in parts[1:])
    if name in SWIFT_KEYWORDS or keyword.iskeyword(name):
        name = f"`{name}`"
    return name


def swift_literal(text):
    escaped = text.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("\t", "\\t")
    return f'"{escaped}"'


def doc_comment(text):
    return text.replace("\n", " ").replace("*/", "* /")


def main():
    english_strings, english_plurals = read(ANDROID_RES / "values/dejavu_strings.xml")
    translations = {}
    for folder in sorted(path.name for path in ANDROID_RES.iterdir() if path.name.startswith("values-")):
        source = ANDROID_RES / folder / "dejavu_strings.xml"
        if source.exists():
            translations[locale_of(folder)] = read(source)

    catalog = {"sourceLanguage": "en", "strings": {}, "version": "1.0"}
    for key in sorted(set(english_strings) | set(english_plurals)):
        localizations = {}
        languages = [("en", (english_strings, english_plurals))] + sorted(translations.items())
        for language, (strings, plurals) in languages:
            if key in english_strings and key in strings:
                localizations[language] = {
                    "stringUnit": {"state": "translated", "value": to_ios_format(strings[key])}
                }
            elif key in english_plurals and key in plurals:
                localizations[language] = {
                    "variations": {
                        "plural": {
                            quantity: {"stringUnit": {"state": "translated", "value": to_ios_format(value)}}
                            for quantity, value in sorted(plurals[key].items())
                        }
                    }
                }
        catalog["strings"][key] = {"extractionState": "manual", "localizations": localizations}

    CATALOG.parent.mkdir(parents=True, exist_ok=True)
    CATALOG.write_text(json.dumps(catalog, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    lines = [
        "// This Source Code Form is subject to the terms of the Mozilla Public",
        "// License, v. 2.0. If a copy of the MPL was not distributed with this",
        "// file, You can obtain one at http://mozilla.org/MPL/2.0/.",
        "",
        "// Generated by tools/dejavu_l10n.py from the Android app's dejavu_strings.xml. Do not edit; run the script.",
        "",
        "import Foundation",
        "",
        "extension L10n {",
    ]
    for key in sorted(set(english_strings) | set(english_plurals)):
        name = accessor_name(key)
        if key in english_strings:
            english = english_strings[key]
            arguments = arguments_of(english)
        else:
            english = english_plurals[key].get("other") or next(iter(english_plurals[key].values()))
            arguments = arguments_of(english) or ["d"]
        lines.append(f"    /// {doc_comment(english)}")
        literal = swift_literal(to_ios_format(english))
        if not arguments:
            lines.append(f'    static var {name}: String {{ tr("{key}", {literal}) }}')
        else:
            parameters = ", ".join(
                f"_ p{index + 1}: {'String' if kind == 's' else 'Int'}" for index, kind in enumerate(arguments)
            )
            values = ", ".join(f"p{index + 1}" for index in range(len(arguments)))
            lines.append(f'    static func {name}({parameters}) -> String {{ tr("{key}", {literal}, {values}) }}')
    lines.append("}")
    SWIFT.parent.mkdir(parents=True, exist_ok=True)
    SWIFT.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"{len(catalog['strings'])} strings in {len(translations) + 1} languages")
    print(f"Wrote {CATALOG.relative_to(TREE)}")
    print(f"Wrote {SWIFT.relative_to(TREE)}")
    print("Languages: " + " ".join(sorted(language for language in translations)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
