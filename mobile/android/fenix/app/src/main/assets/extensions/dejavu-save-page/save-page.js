/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

"use strict";

/*
 * Dejavu: saves the page as it shows into one HTML file that opens without the internet. The page's scripts are left
 * out, its pictures, styles and fonts go inside the file, and the addresses it links to are made full, so that they
 * still lead to the web.
 */

const PORT_NAME = "dejavuSavePage";
// The file goes to the app in parts this long, so that no message is too big.
const PART_LENGTH = 512 * 1024;
// Pictures, styles and fonts bigger than this, past the total, or still loading after the deadline stay links.
const MAX_RESOURCE_BYTES = 10 * 1024 * 1024;
const MAX_TOTAL_BYTES = 40 * 1024 * 1024;
const FETCH_DEADLINE_MS = 45000;
const FETCH_TIMEOUT_MS = 20000;
const PARALLEL_FETCHES = 6;
const MAX_CANVAS_PIXELS = 16 * 1024 * 1024;
const MAX_IMPORT_DEPTH = 4;

const SVG_NS = "http://www.w3.org/2000/svg";
const CSS_URL = /url\(\s*(?:"((?:[^"\\\n]|\\.)*)"|'((?:[^'\\\n]|\\.)*)'|([^)"'\s]*))\s*\)/gi;
const CSS_IMPORT = /@import\s+(?:url\(\s*(?:"([^"]*)"|'([^']*)'|([^)\s]*))\s*\)|"([^"]*)"|'([^']*)')([^;]*);/gi;
const DROPPED_LINKS = ["preload", "prefetch", "modulepreload", "preconnect", "dns-prefetch", "prerender"];
const DROPPED_META = ["refresh", "content-security-policy", "content-type", "set-cookie"];
// The elements a page can give a shadow root; others, like videos, only have the browser's own.
const SHADOW_HOSTS = new Set([
  "article",
  "aside",
  "blockquote",
  "body",
  "div",
  "footer",
  "h1",
  "h2",
  "h3",
  "h4",
  "h5",
  "h6",
  "header",
  "main",
  "nav",
  "p",
  "section",
  "span",
]);

let port = null;

function connect() {
  const own = browser.runtime.connectNative(PORT_NAME);
  own.onMessage.addListener(message => {
    if (message.action === "save") {
      save(own, message.id);
    }
  });
  own.onDisconnect.addListener(() => {
    if (port === own) {
      port = null;
    }
  });
  port = own;
}

connect();

// A page coming back from the back and forward cache does not run this script again.
window.addEventListener("pageshow", () => {
  if (!port) {
    connect();
  }
});

window.addEventListener("pagehide", () => {
  port?.disconnect();
  port = null;
});

async function save(target, id) {
  try {
    const { html, resources } = await snapshot();
    const sender = new Sender(target, id);
    const standIns = new RegExp(`${resources.prefix}(\\d+)`, "g");
    let last = 0;
    for (const match of html.matchAll(standIns)) {
      sender.write(html.slice(last, match.index));
      sender.write(resources.data[Number(match[1])] ?? "");
      last = match.index + match[0].length;
    }
    sender.write(html.slice(last));
    sender.flush();
    target.postMessage({ id, done: true });
  } catch (error) {
    try {
      target.postMessage({ id, error: String(error) });
    } catch (e) {
      // The page is gone, which the app learns from its port.
    }
  }
}

/** Sends the file in parts of [PART_LENGTH], never splitting a character in two. */
class Sender {
  constructor(target, id) {
    this.target = target;
    this.id = id;
    this.buffer = "";
  }

  write(text) {
    this.buffer += text;
    while (this.buffer.length >= PART_LENGTH) {
      let end = PART_LENGTH;
      if (isHighSurrogate(this.buffer.charCodeAt(end - 1))) {
        end--;
      }
      this.target.postMessage({ id: this.id, part: this.buffer.slice(0, end) });
      this.buffer = this.buffer.slice(end);
    }
  }

  flush() {
    if (this.buffer) {
      this.target.postMessage({ id: this.id, part: this.buffer });
      this.buffer = "";
    }
  }
}

function isHighSurrogate(code) {
  return code >= 0xd800 && code <= 0xdbff;
}

/**
 * The page as HTML, with stand-ins for the pictures, styles and fonts that go inside the file as data URLs, which
 * [Resources] keeps: they are put in as the file is sent, so the page's copy stays small.
 */
async function snapshot() {
  const root = document.documentElement;
  if (!root) {
    throw new Error("The page has no content");
  }
  const doc = document.implementation.createHTMLDocument("");
  const copy = doc.importNode(root, true);
  const context = { doc, resources: new Resources(), jobs: [] };
  copyTree(root, copy, context);
  const body = [...copy.children].find(child => child.localName === "body") ?? copy;
  const adopted = adoptedSheets(document);
  if (adopted.length) {
    context.jobs.push(sheetsCss(adopted, context).then(css => appendStyle(body, css, context)));
  }
  while (context.jobs.length) {
    await Promise.all(context.jobs.splice(0));
  }
  cleanTree(copy);
  const head = [...copy.children].find(child => child.localName === "head");
  if (head) {
    const charset = doc.createElement("meta");
    charset.setAttribute("charset", "utf-8");
    head.insertBefore(charset, head.firstChild);
  }
  const savedFrom = `<!-- Saved from ${location.href.replace(/--/g, "%2D%2D")} -->\n`;
  return { html: doctype() + savedFrom + copy.outerHTML, resources: context.resources };
}

function doctype() {
  const type = document.doctype;
  if (!type) {
    return "";
  }
  let text = `<!DOCTYPE ${type.name}`;
  if (type.publicId) {
    text += ` PUBLIC "${type.publicId}"`;
  } else if (type.systemId) {
    text += " SYSTEM";
  }
  if (type.systemId) {
    text += ` "${type.systemId}"`;
  }
  return `${text}>\n`;
}

function treeElements(root) {
  const elements = [...root.querySelectorAll("*")];
  return root.nodeType === Node.ELEMENT_NODE ? [root, ...elements] : elements;
}

/** Fills [copy] with what only the shown page knows: the pictures it chose, its styles, canvases and shadow roots. */
function copyTree(source, copy, context) {
  const sources = treeElements(source);
  const copies = treeElements(copy);
  if (sources.length !== copies.length) {
    return;
  }
  for (let i = 0; i < sources.length; i++) {
    if (sources[i].localName === copies[i].localName) {
      copyElement(sources[i], copies[i], context);
    }
  }
}

function copyElement(source, copy, context) {
  const { jobs, resources } = context;
  if (copy.namespaceURI === SVG_NS) {
    if (copy.localName === "image" || copy.localName === "feImage") {
      jobs.push(inlineSvgImage(copy, resources));
    } else if (copy.localName === "style") {
      jobs.push(copyStyle(source, copy, resources));
    }
  } else {
    switch (copy.localName) {
      case "img":
        jobs.push(inlineImage(source, copy, resources));
        break;
      case "input":
        if (source.type === "image" && source.src) {
          jobs.push(inlineAttribute(copy, "src", source.src, resources));
        }
        break;
      case "video":
        if (source.poster) {
          jobs.push(inlineAttribute(copy, "poster", source.poster, resources));
        }
        break;
      case "link":
        copyLink(source, copy, context);
        break;
      case "style":
        jobs.push(copyStyle(source, copy, resources));
        break;
      case "canvas":
        paintCanvas(source, copy, resources);
        break;
    }
  }
  const style = copy.getAttribute("style");
  if (style && style.includes("url(")) {
    jobs.push(inlineCss(style, document.baseURI, resources).then(css => copy.setAttribute("style", css)));
  }
  const shadow = source.openOrClosedShadowRoot ?? source.shadowRoot;
  if (shadow && (SHADOW_HOSTS.has(copy.localName) || copy.localName.includes("-"))) {
    copyShadow(shadow, copy, context);
  }
}

/** Puts [shadow], the shadow root of the page's element, into [host], its copy, as a declarative shadow root. */
function copyShadow(shadow, host, context) {
  const template = context.doc.createElement("template");
  template.setAttribute("shadowrootmode", "open");
  for (const child of shadow.childNodes) {
    template.content.appendChild(context.doc.importNode(child, true));
  }
  host.insertBefore(template, host.firstChild);
  copyTree(shadow, template.content, context);
  const adopted = adoptedSheets(shadow);
  if (adopted.length) {
    context.jobs.push(sheetsCss(adopted, context).then(css => appendStyle(template.content, css, context)));
  }
}

/** The style sheets a script gave [root], a document or a shadow root, which come after its own. */
function adoptedSheets(root) {
  try {
    return [...(root.adoptedStyleSheets ?? [])];
  } catch (e) {
    return [];
  }
}

async function inlineImage(source, copy, resources) {
  const url = source.currentSrc || source.src;
  if (!url) {
    return;
  }
  let data = await resources.inline(url);
  if (!data && source.complete && source.naturalWidth) {
    data = resources.keep(drawnPicture(source));
  }
  if (!data) {
    copy.setAttribute("src", url);
    return;
  }
  copy.setAttribute("src", data);
  for (const name of ["srcset", "sizes", "loading"]) {
    copy.removeAttribute(name);
  }
  // The sources of a picture would win over the picture now inside the file.
  const picture = copy.parentElement;
  if (picture?.localName === "picture") {
    for (const child of [...picture.children]) {
      if (child.localName === "source") {
        child.remove();
      }
    }
  }
}

/** [image] drawn as a data URL, for a picture that cannot be loaded again, or null when its site does not allow it. */
function drawnPicture(image) {
  if (image.naturalWidth * image.naturalHeight > MAX_CANVAS_PIXELS) {
    return null;
  }
  try {
    const canvas = document.createElement("canvas");
    canvas.width = image.naturalWidth;
    canvas.height = image.naturalHeight;
    canvas.getContext("2d").drawImage(image, 0, 0);
    return canvas.toDataURL();
  } catch (e) {
    return null;
  }
}

async function inlineAttribute(copy, name, url, resources) {
  copy.setAttribute(name, (await resources.inline(url)) ?? url);
}

async function inlineSvgImage(copy, resources) {
  const attribute = [...copy.attributes].find(candidate => candidate.localName === "href");
  if (!attribute || attribute.value.trim().startsWith("#")) {
    return;
  }
  const url = resolved(attribute.value, document.baseURI);
  attribute.value = (await resources.inline(url)) ?? url;
}

/** Keeps what a canvas shows as its background picture, since its drawing does not come along with it. */
function paintCanvas(source, copy, resources) {
  if (!source.width || !source.height || source.width * source.height > MAX_CANVAS_PIXELS) {
    return;
  }
  let data;
  try {
    data = resources.keep(source.toDataURL());
  } catch (e) {
    return;
  }
  if (!data) {
    return;
  }
  const style = (copy.getAttribute("style") ?? "").trim();
  const separator = style && !style.endsWith(";") ? "; " : " ";
  const picture = `background-image: url("${data}"); background-size: 100% 100%; background-repeat: no-repeat;`;
  copy.setAttribute("style", `${style}${separator}${picture}`.trim());
}

function copyLink(source, copy, context) {
  const rel = relOf(copy);
  if (rel.includes("stylesheet") && !rel.includes("alternate")) {
    const sheet = source.sheet;
    if (!sheet || sheet.disabled) {
      return;
    }
    context.jobs.push(
      sheetCss(sheet, context.resources, 0).then(css => {
        if (css == null) {
          return;
        }
        const style = context.doc.createElement("style");
        for (const name of ["media", "title"]) {
          const value = copy.getAttribute(name);
          if (value) {
            style.setAttribute(name, value);
          }
        }
        style.textContent = escapeStyle(css);
        copy.replaceWith(style);
      })
    );
  } else if (rel.includes("icon") && source.href) {
    context.jobs.push(inlineAttribute(copy, "href", source.href, context.resources));
  }
}

async function copyStyle(source, copy, resources) {
  const text = copy.textContent;
  let css = null;
  if (text.trim()) {
    css = await textCss(text, document.baseURI, resources, 0);
  } else if (source.sheet) {
    // Libraries that put their rules in with insertRule leave the element empty.
    css = await sheetCss(source.sheet, resources, 0);
  }
  if (css != null) {
    copy.textContent = escapeStyle(css);
  }
}

function appendStyle(parent, css, context) {
  if (!css) {
    return;
  }
  const style = context.doc.createElement("style");
  style.textContent = escapeStyle(css);
  parent.appendChild(style);
}

async function sheetsCss(sheets, context) {
  const parts = await Promise.all(sheets.map(sheet => sheetCss(sheet, context.resources, 0)));
  return parts.filter(Boolean).join("\n");
}

/** The rules of [sheet] as text, read from the sheet, or from the web for a sheet of another site. */
async function sheetCss(sheet, resources, depth) {
  const base = sheet.href || document.baseURI;
  let rules = null;
  try {
    rules = sheet.cssRules;
  } catch (e) {
    // Another site's sheet keeps its rules to itself.
  }
  if (rules) {
    return rulesCss(rules, base, resources, depth);
  }
  if (!sheet.href) {
    return null;
  }
  const text = await resources.text(sheet.href);
  return text == null ? null : textCss(text, sheet.href, resources, depth);
}

async function rulesCss(rules, base, resources, depth) {
  const parts = [];
  for (const rule of rules) {
    if (rule.type === CSSRule.IMPORT_RULE) {
      const imported =
        rule.styleSheet && depth < MAX_IMPORT_DEPTH ? await sheetCss(rule.styleSheet, resources, depth + 1) : null;
      parts.push(imported == null ? absoluteCss(rule.cssText, base) : withMedia(imported, rule.media.mediaText));
    } else if (rule.type === CSSRule.FONT_FACE_RULE) {
      parts.push(fontFaceCss(rule));
    } else {
      parts.push(rule.cssText);
    }
  }
  return inlineCss(parts.join("\n"), base, resources);
}

/** Style sheet text with the sheets it imports put in its place. */
async function textCss(text, base, resources, depth) {
  let css = "";
  let last = 0;
  for (const match of text.matchAll(CSS_IMPORT)) {
    css += text.slice(last, match.index);
    last = match.index + match[0].length;
    const address = match[1] ?? match[2] ?? match[3] ?? match[4] ?? match[5] ?? "";
    const url = absolute(unescapeCss(address), base);
    // Layers and support conditions are dropped: the rules apply as if they were not in a layer.
    const media = match[6].replace(/\blayer(\([^)]*\))?|\bsupports\([^)]*\)/gi, "").trim();
    const imported = depth < MAX_IMPORT_DEPTH ? await resources.text(url) : null;
    css +=
      imported == null
        ? `@import url("${cssString(url)}")${media ? ` ${media}` : ""};`
        : withMedia(await textCss(imported, url, resources, depth + 1), media);
  }
  css += text.slice(last);
  return inlineCss(css, base, resources);
}

function withMedia(css, media) {
  return media && media !== "all" ? `@media ${media} {\n${css}\n}` : css;
}

/** A font face that loads only the first of its font files, the one a browser picks, rather than all of them. */
function fontFaceCss(rule) {
  try {
    const style = rule.style;
    const declarations = [];
    for (let i = 0; i < style.length; i++) {
      const name = style.item(i);
      const value = style.getPropertyValue(name);
      declarations.push(`${name}: ${name === "src" ? firstFontFile(value) : value};`);
    }
    return declarations.length ? `@font-face { ${declarations.join(" ")} }` : rule.cssText;
  } catch (e) {
    return rule.cssText;
  }
}

function firstFontFile(src) {
  const sources = splitTopLevel(src);
  const file = sources.find(source => /^url\(/i.test(source));
  return sources.filter(source => !/^url\(/i.test(source) || source === file).join(", ");
}

function splitTopLevel(text) {
  const parts = [];
  let depth = 0;
  let quote = null;
  let start = 0;
  for (let i = 0; i < text.length; i++) {
    const char = text[i];
    if (quote) {
      if (char === "\\") {
        i++;
      } else if (char === quote) {
        quote = null;
      }
    } else if (char === '"' || char === "'") {
      quote = char;
    } else if (char === "(") {
      depth++;
    } else if (char === ")") {
      depth--;
    } else if (char === "," && depth === 0) {
      parts.push(text.slice(start, i));
      start = i + 1;
    }
  }
  parts.push(text.slice(start));
  return parts.map(part => part.trim()).filter(Boolean);
}

/** [css] with the pictures and fonts it uses put inside it, and the others made full addresses. */
async function inlineCss(css, base, resources) {
  const found = new Map();
  for (const match of css.matchAll(CSS_URL)) {
    const url = cssUrl(match, base);
    if (url && !found.has(url)) {
      found.set(url, resources.inline(url));
    }
  }
  if (!found.size) {
    return css;
  }
  const data = new Map();
  for (const [url, promise] of found) {
    data.set(url, await promise);
  }
  return css.replace(CSS_URL, (...match) => {
    const url = cssUrl(match, base);
    return url ? `url("${cssString(data.get(url) ?? url)}")` : match[0];
  });
}

function absoluteCss(css, base) {
  return css.replace(CSS_URL, (...match) => {
    const url = cssUrl(match, base);
    return url ? `url("${cssString(url)}")` : match[0];
  });
}

/** The full address a url() of CSS points to, or null for one inside the file, like a data URL or a #fragment. */
function cssUrl(match, base) {
  const value = unescapeCss(match[1] ?? match[2] ?? match[3] ?? "").trim();
  if (!value || value.startsWith("#") || /^data:/i.test(value)) {
    return null;
  }
  return absolute(value, base);
}

function unescapeCss(value) {
  return value.replace(/\\(?:([0-9a-fA-F]{1,6})\s?|(.))/g, (match, hex, char) => {
    if (!hex) {
      return char;
    }
    const code = parseInt(hex, 16);
    return code > 0 && code <= 0x10ffff ? String.fromCodePoint(code) : "�";
  });
}

function cssString(value) {
  return value.replace(/\\/g, "\\\\").replace(/"/g, '\\"').replace(/\n/g, "\\a ");
}

/** CSS that cannot end the style element it is in. */
function escapeStyle(css) {
  return css.replace(/<\/(style)/gi, "<\\/$1");
}

/**
 * Takes out what would run, load or go somewhere by itself: scripts, event handlers, refreshes, preloads and the
 * page's own rules on what may load, and makes the addresses left full, relative to the page.
 */
function cleanTree(root) {
  const base = document.baseURI;
  for (const element of treeElements(root)) {
    if (isDropped(element)) {
      element.remove();
      continue;
    }
    for (const attribute of [...element.attributes]) {
      const name = attribute.name.toLowerCase();
      if (name.startsWith("on") || name === "ping" || name === "integrity" || name === "nonce") {
        element.removeAttribute(attribute.name);
      }
    }
    makeAddressesFull(element, base);
    const style = element.getAttribute("style");
    if (style && style.includes("url(")) {
      element.setAttribute("style", absoluteCss(style, base));
    }
    if (element.localName === "template" && element.content) {
      cleanTree(element.content);
    }
  }
}

function isDropped(element) {
  switch (element.localName) {
    case "script":
      return true;
    case "base":
      return element.namespaceURI !== SVG_NS;
    case "link": {
      const rel = relOf(element);
      return DROPPED_LINKS.some(name => rel.includes(name));
    }
    case "meta":
      return (
        element.hasAttribute("charset") ||
        DROPPED_META.includes((element.getAttribute("http-equiv") ?? "").trim().toLowerCase())
      );
    default:
      return false;
  }
}

function relOf(element) {
  return (element.getAttribute("rel") ?? "").toLowerCase().split(/\s+/);
}

function makeAddressesFull(element, base) {
  if (element.namespaceURI === SVG_NS) {
    for (const attribute of element.attributes) {
      if (attribute.localName === "href" && !attribute.value.trim().startsWith("#")) {
        attribute.value = resolved(attribute.value, base);
      }
    }
    return;
  }
  for (const name of addressAttributes(element.localName)) {
    const value = element.getAttribute(name);
    if (value != null) {
      const isLink = name === "href" && (element.localName === "a" || element.localName === "area");
      element.setAttribute(name, isLink ? linkTarget(value, base) : resolved(value, base));
    }
  }
  for (const name of ["srcset", "imagesrcset"]) {
    const value = element.getAttribute(name);
    if (value) {
      element.setAttribute(name, resolvedSrcset(value, base));
    }
  }
}

function addressAttributes(name) {
  switch (name) {
    case "a":
    case "area":
    case "link":
      return ["href"];
    case "img":
    case "iframe":
    case "frame":
    case "embed":
    case "audio":
    case "source":
    case "track":
      return ["src"];
    case "video":
      return ["src", "poster"];
    case "input":
      return ["src", "formaction"];
    case "button":
      return ["formaction"];
    case "form":
      return ["action"];
    case "object":
      return ["data"];
    case "blockquote":
    case "q":
    case "del":
    case "ins":
      return ["cite"];
    case "body":
    case "table":
    case "td":
    case "th":
      return ["background"];
    default:
      return [];
  }
}

/** Where a link leads: a place in the saved page itself stays a #fragment, other pages are full addresses. */
function linkTarget(value, base) {
  const url = resolved(value, base);
  try {
    const target = new URL(url);
    const page = new URL(document.URL);
    const samePage =
      target.origin === page.origin && target.pathname === page.pathname && target.search === page.search;
    if (samePage && target.hash) {
      return target.hash;
    }
  } catch (e) {
    // Not an address of the web, like a mailto: link.
  }
  return url;
}

/** [value] as a full address against [base], or as it is when it already points somewhere by itself. */
function resolved(value, base) {
  const trimmed = value.trim();
  if (!trimmed || trimmed.startsWith("#") || /^(data|javascript|mailto|tel|sms|blob|about):/i.test(trimmed)) {
    return value;
  }
  return absolute(trimmed, base);
}

function absolute(value, base) {
  try {
    return new URL(value, base).href;
  } catch (e) {
    return value;
  }
}

function resolvedSrcset(srcset, base) {
  const candidates = [];
  let i = 0;
  while (i < srcset.length) {
    while (i < srcset.length && /[\s,]/.test(srcset[i])) {
      i++;
    }
    let start = i;
    while (i < srcset.length && !/\s/.test(srcset[i])) {
      i++;
    }
    let url = srcset.slice(start, i);
    let descriptors = "";
    if (url.endsWith(",")) {
      url = url.replace(/,+$/, "");
    } else {
      start = i;
      while (i < srcset.length && srcset[i] !== ",") {
        i++;
      }
      descriptors = srcset.slice(start, i).trim();
    }
    if (url) {
      candidates.push(descriptors ? `${resolved(url, base)} ${descriptors}` : resolved(url, base));
    }
  }
  return candidates.join(", ");
}

/**
 * The pictures, styles and fonts that go inside the file. They are loaded again, from the cache where they still are,
 * a few at a time, and each gets a short stand-in in the page's copy until the file is sent. The stand-ins look like
 * data URLs, so that nothing takes them for addresses to make full.
 */
class Resources {
  constructor() {
    this.prefix = `data:dejavu-${Math.random().toString(36).slice(2, 10)},`;
    this.data = [];
    this.loads = new Map();
    this.total = 0;
    this.active = 0;
    this.waiting = [];
    this.deadline = Date.now() + FETCH_DEADLINE_MS;
  }

  /** A stand-in for the file at [url], or null when it stays a link. */
  inline(url) {
    if (/^data:/i.test(url)) {
      return Promise.resolve(url);
    }
    if (!this.loads.has(url)) {
      this.loads.set(
        url,
        this.limit(async () => {
          const blob = await this.load(url);
          return blob ? this.keep(await readAsDataUrl(blob), false) : null;
        })
      );
    }
    return this.loads.get(url);
  }

  /** The text at [url], like a style sheet, or null. */
  text(url) {
    return this.limit(async () => {
      const blob = await this.load(url);
      if (!blob) {
        return null;
      }
      const charset = /charset=["']?([\w-]+)/i.exec(blob.type)?.[1] ?? "utf-8";
      try {
        return new TextDecoder(charset).decode(await blob.arrayBuffer());
      } catch (e) {
        return blob.text();
      }
    });
  }

  /** A stand-in for [data], a data URL, or null when the file is full. */
  keep(data, count = true) {
    if (!data || (count && !this.claim(data.length))) {
      return null;
    }
    this.data.push(data);
    return this.prefix + (this.data.length - 1);
  }

  claim(bytes) {
    if (this.total + bytes > MAX_TOTAL_BYTES) {
      return false;
    }
    this.total += bytes;
    return true;
  }

  async limit(task) {
    while (this.active >= PARALLEL_FETCHES) {
      await new Promise(resolve => this.waiting.push(resolve));
    }
    this.active++;
    try {
      return await task();
    } finally {
      this.active--;
      this.waiting.shift()?.();
    }
  }

  async load(url) {
    const left = this.deadline - Date.now();
    if (left <= 0 || !/^(https?|blob):/i.test(url)) {
      return null;
    }
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), Math.min(FETCH_TIMEOUT_MS, left));
    try {
      const response = await fetch(url, { cache: "force-cache", credentials: "include", signal: controller.signal });
      if (!response.ok) {
        return null;
      }
      const blob = await response.blob();
      return blob.size <= MAX_RESOURCE_BYTES && this.claim(blob.size) ? blob : null;
    } catch (e) {
      return null;
    } finally {
      clearTimeout(timer);
    }
  }
}

/** [blob] as a base64 data URL, with a plain type that is safe anywhere in the file. */
function readAsDataUrl(blob) {
  return new Promise(resolve => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = reader.result;
      const comma = result.indexOf(",");
      const type = blob.type.split(";")[0].trim().toLowerCase();
      const safeType = /^[\w.+-]+\/[\w.+-]+$/.test(type) ? type : "application/octet-stream";
      resolve(comma < 0 ? null : `data:${safeType};base64,${result.slice(comma + 1)}`);
    };
    reader.onerror = () => resolve(null);
    reader.readAsDataURL(blob);
  });
}
