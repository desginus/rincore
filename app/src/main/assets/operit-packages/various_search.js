/* METADATA
{
  name: various_search

  display_name: {
    zh: "多平台搜索"
    en: "Multi-Platform Search"
  }
  category: "Search"
  description: { zh: "提供多平台搜索功能（含图片搜索），支持从必应、百度、搜狗、夸克等平台获取搜索结果。", en: "Multi-platform search tools (including image search) that fetch results from Bing, Baidu, Sogou, Quark, and more." }
  enabledByDefault: true
  
  tools: [
    {
      name: search_bing
      description: { zh: "使用必应搜索引擎进行搜索", en: "Search using the Bing search engine." }
      parameters: [
        {
          name: query
          description: { zh: "搜索查询关键词", en: "Search query keywords." }
          type: string
          required: true
        },
        {
          name: includeLinks
          description: { zh: "是否在结果中包含可点击的链接列表，默认为false。如果为true，AI可以根据返回的链接序号进行深入访问。", en: "Whether to include a clickable link list in results (default: false). If true, the AI can follow links by index." }
          type: boolean
          required: false
        }
      ]
    },
    {
      name: search_baidu
      description: { zh: "使用百度搜索引擎进行搜索", en: "Search using the Baidu search engine." }
      parameters: [
        {
          name: query
          description: { zh: "搜索查询关键词", en: "Search query keywords." }
          type: string
          required: true
        },
        {
          name: page
          description: { zh: "搜索结果页码，默认为1", en: "Result page number (default: 1)." }
          type: string
          required: false
        },
        {
          name: includeLinks
          description: { zh: "是否在结果中包含可点击的链接列表，默认为false。如果为true，AI可以根据返回的链接序号进行深入访问。", en: "Whether to include a clickable link list in results (default: false). If true, the AI can follow links by index." }
          type: boolean
          required: false
        }
      ]
    },
    {
      name: search_sogou
      description: { zh: "使用搜狗搜索引擎进行搜索", en: "Search using the Sogou search engine." }
      parameters: [
        {
          name: query
          description: { zh: "搜索查询关键词", en: "Search query keywords." }
          type: string
          required: true
        },
        {
          name: page
          description: { zh: "搜索结果页码，默认为1", en: "Result page number (default: 1)." }
          type: string
          required: false
        },
        {
          name: includeLinks
          description: { zh: "是否在结果中包含可点击的链接列表，默认为false。如果为true，AI可以根据返回的链接序号进行深入访问。", en: "Whether to include a clickable link list in results (default: false). If true, the AI can follow links by index." }
          type: boolean
          required: false
        }
      ]
    },
    {
      name: search_quark
      description: { zh: "使用夸克搜索引擎进行搜索", en: "Search using the Quark search engine." }
      parameters: [
        {
          name: query
          description: { zh: "搜索查询关键词", en: "Search query keywords." }
          type: string
          required: true
        },
        {
          name: page
          description: { zh: "搜索结果页码，默认为1", en: "Result page number (default: 1)." }
          type: string
          required: false
        },
        {
          name: includeLinks
          description: { zh: "是否在结果中包含可点击的链接列表，默认为false。如果为true，AI可以根据返回的链接序号进行深入访问。", en: "Whether to include a clickable link list in results (default: false). If true, the AI can follow links by index." }
          type: boolean
          required: false
        }
      ]
    },
    {
      name: combined_search
      description: { zh: "在多个平台同时执行搜索。建议用户要求搜索的时候默认使用这个工具。", en: "Run searches across multiple platforms. Use this tool by default when the user asks to search." }
      parameters: [
        {
          name: query
          description: { zh: "搜索查询关键词", en: "Search query keywords." }
          type: string
          required: true
        },
        {
          name: platforms
          description: { zh: "搜索平台列表字符串，可选值包括\"bing\",\"baidu\",\"sogou\",\"quark\"，多个平台用逗号分隔，比如\"bing,baidu,sogou,quark\"", en: "Comma-separated platform list. Supported: \"bing\", \"baidu\", \"sogou\", \"quark\". Example: \"bing,baidu,sogou,quark\"." }
          type: string
          required: true
        },
        {
          name: includeLinks
          description: { zh: "是否在结果中包含可点击的链接列表，默认为false。聚合搜索时建议保持为false以节省输出，仅在需要深入访问时对单个搜索引擎使用。", en: "Whether to include a clickable link list in results (default: false). For combined search, keep it false to reduce output; enable it for a single engine when you need to open links." }
          type: boolean
          required: false
        }
      ]
    },
    {
      name: search
      description: { zh: "兼容工具名：等同于 combined_search。用于处理模型误调用 search 的情况。", en: "Compatibility alias: equivalent to combined_search. Helps when models call search by mistake." }
      parameters: [
        {
          name: query
          description: { zh: "搜索查询关键词", en: "Search query keywords." }
          type: string
          required: true
        },
        {
          name: platforms
          description: { zh: "可选平台列表，默认 bing,baidu,sogou,quark", en: "Optional platform list, default bing,baidu,sogou,quark." }
          type: string
          required: false
        },
        {
          name: includeLinks
          description: { zh: "是否返回链接列表，默认 false", en: "Whether to include links in result, default false." }
          type: boolean
          required: false
        }
      ]
    },
    {
      name: search_web
      description: { zh: "兼容工具名：网页搜索别名。默认复用 combined_search 的统一搜索逻辑。", en: "Compatibility alias for web search. Reuses the unified combined_search flow." }
      parameters: [
        {
          name: query
          description: { zh: "搜索查询关键词", en: "Search query keywords." }
          type: string
          required: true
        },
        {
          name: platforms
          description: { zh: "可选平台列表，默认 bing,baidu,sogou,quark", en: "Optional platform list, default bing,baidu,sogou,quark." }
          type: string
          required: false
        },
        {
          name: includeLinks
          description: { zh: "是否返回链接列表，默认 false", en: "Whether to include links in result, default false." }
          type: boolean
          required: false
        }
      ]
    },
    {
      name: search_bing_images
      description: { zh: "使用必应图片搜索引擎进行图片搜索。返回内容会包含 visitKey 和 Images 编号；下载图片请用 download_file 的 visit_key + image_number（不要用 link_number 乱点页面链接）。", en: "Search images using Bing Images. The result includes visitKey and indexed Images; download images via download_file with visit_key + image_number (do not follow random page links via link_number)." }
      parameters: [
        {
          name: query
          description: { zh: "搜索关键词", en: "Search query keywords." }
          type: string
          required: true
        }
      ]
    },
    {
      name: search_wikimedia_images
      description: { zh: "使用 Wikimedia Commons 进行图片搜索（公共资源）。返回 visitKey + Images 编号；下载图片用 download_file(visit_key + image_number)。", en: "Search images using Wikimedia Commons (public domain/commons). Use visitKey + image_number with download_file to download images." }
      parameters: [
        {
          name: query
          description: { zh: "搜索关键词", en: "Search query keywords." }
          type: string
          required: true
        }
      ]
    },
    {
      name: search_duckduckgo_images
      description: { zh: "使用 DuckDuckGo Images 进行图片搜索。返回 visitKey + Images 编号；下载图片用 download_file(visit_key + image_number)。", en: "Search images using DuckDuckGo Images. Use visitKey + image_number with download_file to download images." }
      parameters: [
        {
          name: query
          description: { zh: "搜索关键词", en: "Search query keywords." }
          type: string
          required: true
        }
      ]
    },
    {
      name: search_ecosia_images
      description: { zh: "使用 Ecosia Images 进行图片搜索。返回 visitKey + Images 编号；下载图片用 download_file(visit_key + image_number)。", en: "Search images using Ecosia Images. Use visitKey + image_number with download_file to download images." }
      parameters: [
        {
          name: query
          description: { zh: "搜索关键词", en: "Search query keywords." }
          type: string
          required: true
        }
      ]
    },
    {
      name: search_pexels_images
      description: { zh: "使用 Pexels 进行图片搜索（高质量图库）。返回 visitKey + Images 编号；下载图片请用 download_file 的 visit_key + image_number。", en: "Search images using Pexels (high-quality stock). Use visitKey + image_number with download_file to download images." }
      parameters: [
        {
          name: query
          description: { zh: "搜索关键词", en: "Search query keywords." }
          type: string
          required: true
        }
      ]
    },
    {
      name: search_pixabay_images
      description: { zh: "使用 Pixabay 进行图片搜索（图库）。返回 visitKey + Images 编号；下载图片请用 download_file 的 visit_key + image_number。", en: "Search images using Pixabay (stock). Use visitKey + image_number with download_file to download images." }
      parameters: [
        {
          name: query
          description: { zh: "搜索关键词", en: "Search query keywords." }
          type: string
          required: true
        }
      ]
    }
  ]
}*/
const various_search = (function () {
    function normalizeText(input) {
        if (!input)
            return "";
        return String(input).toLowerCase().replace(/[\s\-_.,;:!?()[\]{}"'`~@#$%^&*+=|\\/<>]+/g, "");
    }
    const OUTBOUND_PATH_HINTS = [
        "/link",
        "/url",
        "/redirect",
        "/jump",
        "/out",
        "/outlink",
        "/ulink",
        "/aladdin.php"
    ];
    const OUTBOUND_QUERY_KEYS = [
        "url",
        "u",
        "target",
        "targeturl",
        "target_url",
        "to",
        "dest",
        "destination",
        "redirect",
        "redirect_url"
    ];
    const MULTI_PART_TLDS = [
        "ac.uk",
        "co.jp",
        "co.uk",
        "com.au",
        "com.cn",
        "com.hk",
        "gov.cn",
        "net.au",
        "net.cn",
        "org.au",
        "org.cn",
        "org.uk"
    ];
    function normalizePath(pathname) {
        const normalized = String(pathname || "").replace(/\/+$/g, "");
        return normalized || "/";
    }
    function parseUrlParts(rawUrl, baseUrl) {
        const rawText = String(rawUrl || "").trim();
        if (!rawText) {
            return null;
        }
        let absoluteText = rawText;
        if (absoluteText.startsWith("//")) {
            absoluteText = `https:${absoluteText}`;
        }
        else if (!/^[a-z]+:\/\//i.test(absoluteText)) {
            if (!absoluteText.startsWith("/")) {
                return null;
            }
            const base = baseUrl ? parseUrlParts(baseUrl, "") : null;
            if (!base) {
                return null;
            }
            absoluteText = `${base.protocol}//${base.host}${absoluteText}`;
        }
        const match = absoluteText.match(/^(https?):\/\/([^\/?#]+)([^?#]*)(\?[^#]*)?(?:#.*)?$/i);
        if (!match) {
            return null;
        }
        const protocol = `${match[1].toLowerCase()}:`;
        const host = match[2].toLowerCase();
        const hostname = host.replace(/:\d+$/, "");
        const pathname = normalizePath(match[3] || "/");
        const search = match[4] || "";
        return {
            href: `${protocol}//${host}${pathname}${search}`,
            protocol,
            host,
            hostname,
            pathname,
            search
        };
    }
    function normalizeUrl(rawUrl, baseUrl) {
        const parsed = parseUrlParts(rawUrl, baseUrl);
        return parsed ? parsed.href : "";
    }
    function parseSearchEntries(searchText) {
        const search = String(searchText || "").replace(/^\?/, "");
        if (!search) {
            return [];
        }
        return search
            .split("&")
            .map((item) => item.trim())
            .filter(Boolean)
            .map((item) => {
            const equalIndex = item.indexOf("=");
            if (equalIndex === -1) {
                return [item, ""];
            }
            return [item.slice(0, equalIndex), item.slice(equalIndex + 1)];
        });
    }
    function getSiteDomain(hostname) {
        const parts = String(hostname || "").toLowerCase().split(".").filter(Boolean);
        if (parts.length <= 2) {
            return String(hostname || "").toLowerCase();
        }
        const lastTwo = parts.slice(-2).join(".");
        if (MULTI_PART_TLDS.includes(lastTwo) && parts.length >= 3) {
            return parts.slice(-3).join(".");
        }
        return lastTwo;
    }
    function countPathSegments(pathname) {
        return normalizePath(pathname).split("/").filter(Boolean).length;
    }
    function getSortedSearch(searchText) {
        return parseSearchEntries(searchText)
            .sort((left, right) => {
            if (left[0] === right[0]) {
                return left[1].localeCompare(right[1]);
            }
            return left[0].localeCompare(right[0]);
        })
            .map(([key, value]) => `${key}=${value}`)
            .join("&");
    }
    function toComparableUrl(rawUrl) {
        if (!rawUrl)
            return "";
        const parsed = parseUrlParts(rawUrl, "");
        if (!parsed) {
            return String(rawUrl).trim();
        }
        const search = getSortedSearch(parsed.search);
        return `${parsed.protocol}//${parsed.host}${parsed.pathname}${search ? `?${search}` : ""}`;
    }
    function getLinkText(link) {
        const text = link && (link.text || link.title || link.name);
        return text ? String(text).replace(/\s+/g, " ").trim() : "";
    }
    function looksLikeUiText(text) {
        const raw = String(text || "").replace(/\s+/g, " ").trim();
        const normalized = normalizeText(text);
        if (!normalized)
            return true;
        if (/^\d+$/.test(normalized))
            return true;
        if (!/[a-z0-9\u4e00-\u9fa5]/i.test(raw))
            return true;
        if (normalized.length === 1)
            return true;
        if (normalized.length === 2 && /^[a-z0-9]+$/i.test(normalized))
            return true;
        return false;
    }
    function looksLikeSearchLandingUrl(rawUrl, sourceUrl) {
        if (!rawUrl || !sourceUrl)
            return false;
        const candidate = parseUrlParts(rawUrl, sourceUrl);
        const source = parseUrlParts(sourceUrl, "");
        if (!candidate || !source)
            return false;
        if (toComparableUrl(candidate.href) === toComparableUrl(source.href)) {
            return true;
        }
        if (candidate.host !== source.host) {
            return false;
        }
        const candidateSearch = getSortedSearch(candidate.search);
        const sourceSearch = getSortedSearch(source.search);
        if (candidate.pathname === "/" && !candidateSearch) {
            return true;
        }
        return candidate.pathname === source.pathname && (!candidateSearch || candidateSearch === sourceSearch);
    }
    function looksLikeOutboundWrapper(rawUrl, sourceUrl) {
        const candidate = parseUrlParts(rawUrl, sourceUrl);
        const source = parseUrlParts(sourceUrl, "");
        if (!candidate || !source || candidate.host !== source.host) {
            return false;
        }
        const path = candidate.pathname.toLowerCase();
        if (OUTBOUND_PATH_HINTS.some((hint) => path === hint || path.endsWith(hint))) {
            return true;
        }
        const search = candidate.search.replace(/^\?/, "");
        return OUTBOUND_QUERY_KEYS.some((key) => new RegExp(`(?:^|&)${key}(?:=|&|$)`, "i").test(search));
    }
    function looksLikeSameSiteSectionUrl(rawUrl, sourceUrl) {
        const candidate = parseUrlParts(rawUrl, sourceUrl);
        const source = parseUrlParts(sourceUrl, "");
        if (!candidate || !source) {
            return false;
        }
        if (candidate.host === source.host) {
            return false;
        }
        if (getSiteDomain(candidate.hostname) !== getSiteDomain(source.hostname)) {
            return false;
        }
        if (looksLikeOutboundWrapper(candidate.href, source.href)) {
            return false;
        }
        return countPathSegments(candidate.pathname) <= 1;
    }
    function classifyLinkTarget(rawUrl, sourceUrl) {
        const text = String(rawUrl || "").trim();
        if (!text) {
            return "invalid";
        }
        const lowered = text.toLowerCase();
        if (lowered.startsWith("javascript:") || lowered.startsWith("#")) {
            return "invalid";
        }
        const candidate = parseUrlParts(rawUrl, sourceUrl);
        const source = parseUrlParts(sourceUrl, "");
        if (!candidate || !source) {
            return "invalid";
        }
        if (looksLikeSearchLandingUrl(candidate.href, source.href)) {
            return "landing";
        }
        if (looksLikeOutboundWrapper(candidate.href, source.href)) {
            return "wrapper";
        }
        if (candidate.host === source.host) {
            return "internal";
        }
        if (looksLikeSameSiteSectionUrl(candidate.href, source.href)) {
            return "internal";
        }
        if (getSiteDomain(candidate.hostname) === getSiteDomain(source.hostname)) {
            return "internal";
        }
        return "external";
    }
    function looksLikeInternalNavigationUrl(rawUrl, sourceUrl) {
        const type = classifyLinkTarget(rawUrl, sourceUrl);
        return type === "invalid" || type === "landing" || type === "internal";
    }
    function extractUrlsFromText(rawText) {
        if (!rawText)
            return [];
        const text = String(rawText);
        const matches = text.match(/https?:\/\/[^\s)\]>"']+/g) || [];
        const unique = [];
        for (const url of matches) {
            if (!unique.includes(url))
                unique.push(url);
        }
        return unique;
    }
    function extractBestUrlFromText(rawText, sourceUrl) {
        const urls = extractUrlsFromText(rawText);
        const meaningful = urls.find((item) => {
            const type = classifyLinkTarget(item, sourceUrl);
            return type === "external" || type === "wrapper";
        });
        return meaningful || urls[0] || "";
    }
    function stripLeadingLinkDump(rawContent) {
        const lines = String(rawContent || "").split(/\r?\n/);
        let index = 0;
        while (index < lines.length && !lines[index].trim()) {
            index++;
        }
        let dumpedLinkCount = 0;
        while (index < lines.length && /^\[\d+\]\s/.test(lines[index].trim())) {
            dumpedLinkCount++;
            index++;
        }
        if (dumpedLinkCount === 0) {
            return String(rawContent || "");
        }
        while (index < lines.length && !lines[index].trim()) {
            index++;
        }
        return lines.slice(index).join('\n');
    }
    function collectLinkCandidates(link, sourceUrl) {
        const values = [
            link?.realUrl,
            link?.real_url,
            link?.targetUrl,
            link?.target_url,
            link?.originUrl,
            link?.origin_url,
            link?.href,
            link?.url,
            link?.target,
            link?.rawUrl,
            link?.link
        ];
        const unique = [];
        for (const value of values) {
            const normalized = normalizeUrl(value ? String(value).trim() : "", sourceUrl);
            if (normalized && !unique.includes(normalized)) {
                unique.push(normalized);
            }
        }
        return unique;
    }
    function pickBestLinkUrl(link, sourceUrl) {
        const candidates = collectLinkCandidates(link, sourceUrl);
        const meaningful = candidates.find((item) => {
            const type = classifyLinkTarget(item, sourceUrl);
            return type === "external" || type === "wrapper";
        });
        if (meaningful)
            return meaningful;
        const fromText = extractBestUrlFromText(getLinkText(link), sourceUrl);
        if (fromText)
            return fromText;
        return candidates[0] || "";
    }
    function scoreStructuralCandidate(text, url, sourceUrl, relativeIndex) {
        let score = 0;
        if (!text || looksLikeUiText(text)) {
            score -= 6;
        }
        else {
            score += 2;
        }
        const type = classifyLinkTarget(url, sourceUrl);
        if (type === "external") {
            score += 5;
        }
        else if (type === "wrapper") {
            score += 4;
        }
        else if (type === "landing" || type === "internal") {
            score -= 8;
        }
        else {
            score -= 4;
        }
        score -= relativeIndex * 0.15;
        return score;
    }
    function findResultBlockStart(links, sourceUrl, scanLimit = 48, windowSize = 6, minCandidates = 3) {
        const upperBound = Math.min(links.length, scanLimit);
        for (let start = 0; start < upperBound; start++) {
            let candidateCount = 0;
            const hosts = [];
            for (let offset = 0; offset < windowSize && start + offset < upperBound; offset++) {
                const link = links[start + offset];
                const text = getLinkText(link);
                const url = pickBestLinkUrl(link, sourceUrl);
                if (!text || looksLikeUiText(text)) {
                    continue;
                }
                const type = classifyLinkTarget(url, sourceUrl);
                if (type !== "external" && type !== "wrapper") {
                    continue;
                }
                candidateCount++;
                const parsed = parseUrlParts(url, sourceUrl);
                const host = parsed ? parsed.hostname : "";
                if (host && !hosts.includes(host)) {
                    hosts.push(host);
                }
            }
            if (candidateCount >= minCandidates && hosts.length >= 2) {
                return start;
            }
        }
        return 0;
    }
    function isClusteredResultLink(links, sourceUrl, targetIndex, radius = 3, minCandidates = 3) {
        let candidateCount = 0;
        const hosts = [];
        const start = Math.max(0, targetIndex - radius);
        const end = Math.min(links.length - 1, targetIndex + radius);
        for (let index = start; index <= end; index++) {
            const link = links[index];
            const text = getLinkText(link);
            const url = pickBestLinkUrl(link, sourceUrl);
            if (!text || looksLikeUiText(text)) {
                continue;
            }
            const type = classifyLinkTarget(url, sourceUrl);
            if (type !== "external" && type !== "wrapper") {
                continue;
            }
            candidateCount++;
            const parsed = parseUrlParts(url, sourceUrl);
            const host = parsed ? parsed.hostname : "";
            if (host && !hosts.includes(host)) {
                hosts.push(host);
            }
        }
        return candidateCount >= minCandidates && hosts.length >= 2;
    }
    function buildProbeIndexes(links, sourceUrl, maxCount = 8, probeWindow = 24) {
        const resultStart = findResultBlockStart(links, sourceUrl);
        return links
            .slice(resultStart, resultStart + probeWindow)
            .map((link, relativeIndex) => {
            const index = resultStart + relativeIndex;
            const text = getLinkText(link);
            const url = pickBestLinkUrl(link, sourceUrl);
            return {
                index,
                score: scoreStructuralCandidate(text, url, sourceUrl, relativeIndex)
            };
        })
            .filter((item) => item.score > -4 && isClusteredResultLink(links, sourceUrl, item.index))
            .slice(0, maxCount)
            .map((item) => item.index);
    }
    async function resolveLinkUrlsByVisitKey(response, sourceUrl, maxCount = 8) {
        if (!response || !response.visitKey || !response.links || !Array.isArray(response.links)) {
            return {};
        }
        const resolved = {};
        const indexes = buildProbeIndexes(response.links, sourceUrl, maxCount);
        const tasks = indexes.map(async (index) => {
            try {
                const follow = await Tools.Net.visit({
                    visit_key: response.visitKey,
                    link_number: index + 1
                });
                const followUrl = follow && follow.url ? normalizeUrl(String(follow.url), sourceUrl) : "";
                const followContent = follow && follow.content ? String(follow.content) : "";
                const contentUrl = extractBestUrlFromText(followContent, sourceUrl);
                resolved[index] = {
                    url: chooseBestUrl("", followUrl, sourceUrl) || chooseBestUrl("", contentUrl, sourceUrl)
                };
            }
            catch (error) {
                console.error(`[resolveLinkUrlsByVisitKey] link ${index + 1} failed: ${error.message}`);
                resolved[index] = { url: "" };
            }
        });
        await Promise.all(tasks);
        return resolved;
    }
    function chooseBestUrl(directUrl, resolvedUrl, sourceUrl) {
        const candidates = [resolvedUrl, directUrl].filter(Boolean);
        for (const candidate of candidates) {
            if (classifyLinkTarget(candidate, sourceUrl) === "external") {
                return normalizeUrl(candidate, sourceUrl);
            }
        }
        return "";
    }
    function shouldKeepLink(links, index, text, url, sourceUrl) {
        if (!url)
            return false;
        if (!text || looksLikeUiText(text))
            return false;
        if (classifyLinkTarget(url, sourceUrl) !== "external") {
            return false;
        }
        return isClusteredResultLink(links, sourceUrl, index);
    }
    async function buildLinkLines(response, sourceUrl, maxItems = 20) {
        const resultStart = findResultBlockStart(response.links, sourceUrl);
        const resolvedByIndex = await resolveLinkUrlsByVisitKey(response, sourceUrl);
        const lines = [];
        const seen = new Set();
        for (let index = resultStart; index < response.links.length; index++) {
            const link = response.links[index];
            const text = getLinkText(link);
            const directUrl = pickBestLinkUrl(link, sourceUrl);
            const resolved = resolvedByIndex[index];
            const bestUrl = chooseBestUrl(directUrl, resolved && resolved.url ? resolved.url : "", sourceUrl);
            if (!shouldKeepLink(response.links, index, text, bestUrl, sourceUrl)) {
                continue;
            }
            const key = toComparableUrl(bestUrl);
            if (!key || seen.has(key)) {
                continue;
            }
            seen.add(key);
            lines.push(`[${index + 1}] ${text} - ${bestUrl}`);
            if (lines.length >= maxItems) {
                break;
            }
        }
        return lines;
    }
    async function performSearch(platform, url, includeLinks = false) {
        try {
            const response = await Tools.Net.visit({ url });
            if (!response) {
                throw new Error(`无法获取 ${platform} 搜索结果`);
            }
            let parts = [];
            let hasFilteredLinks = false;
            if (response.visitKey !== undefined) {
                parts.push(String(response.visitKey));
            }
            if (includeLinks && response.links && Array.isArray(response.links) && response.links.length > 0) {
                const linksLines = await buildLinkLines(response, url);
                if (linksLines.length > 0) {
                    parts.push(linksLines.join('\n'));
                    hasFilteredLinks = true;
                }
            }
            else if (includeLinks && response.content) {
                const extractedUrls = extractUrlsFromText(response.content);
                if (extractedUrls.length > 0) {
                    const lines = extractedUrls.slice(0, 20).map((item, index) => `[${index + 1}] ${item}`);
                    parts.push(lines.join('\n'));
                }
            }
            if (response.content !== undefined && (!includeLinks || !hasFilteredLinks)) {
                const contentText = includeLinks && response.links && Array.isArray(response.links)
                    ? stripLeadingLinkDump(response.content)
                    : String(response.content);
                parts.push(String(contentText));
            }
            return {
                platform,
                content: parts.join('\n')
            };
        }
        catch (error) {
            return {
                platform,
                content: `${platform} 搜索失败: ${error.message}`
            };
        }
    }
    async function performImageSearch(platform, url) {
        try {
            const response = await Tools.Net.visit({ url, include_image_links: true });
            if (!response) {
                throw new Error(`无法获取 ${platform} 图片搜索结果`);
            }
            let parts = [];
            if (response.visitKey !== undefined) {
                parts.push(String(response.visitKey));
            }
            if (response.imageLinks && Array.isArray(response.imageLinks) && response.imageLinks.length > 0) {
                const maxItems = 20;
                const imagesLines = response.imageLinks.slice(0, maxItems).map((link, index) => {
                    const lastSeg = String(link).split('/').pop() || 'image';
                    const name = lastSeg.split('?')[0] || 'image';
                    return `[${index + 1}] ${name}`;
                });
                parts.push("Images:");
                parts.push(imagesLines.join('\n'));
            }
            if (response.content !== undefined) {
                parts.push(String(response.content));
            }
            return {
                platform,
                content: parts.join('\n')
            };
        }
        catch (error) {
            return {
                platform,
                content: `${platform} 图片搜索失败: ${error.message}`
            };
        }
    }
    async function search_bing(query, includeLinks = false) {
        const encodedQuery = encodeURIComponent(query);
        const url = `https://cn.bing.com/search?q=${encodedQuery}&FORM=HDRSC1`;
        return performSearch('bing', url, includeLinks);
    }
    async function search_baidu(query, pageStr, includeLinks = false) {
        let page = 1;
        if (pageStr) {
            page = parseInt(pageStr, 10);
        }
        const pn = (page - 1) * 10;
        const encodedQuery = encodeURIComponent(query);
        const url = `https://www.baidu.com/s?wd=${encodedQuery}&pn=${pn}`;
        return performSearch('baidu', url, includeLinks);
    }
    async function search_sogou(query, pageStr, includeLinks = false) {
        let page = 1;
        if (pageStr) {
            page = parseInt(pageStr, 10);
        }
        const encodedQuery = encodeURIComponent(query);
        const url = `https://www.sogou.com/web?query=${encodedQuery}&page=${page}`;
        return performSearch('sogou', url, includeLinks);
    }
    async function search_quark(query, pageStr, includeLinks = false) {
        let page = 1;
        if (pageStr) {
            page = parseInt(pageStr, 10);
        }
        const encodedQuery = encodeURIComponent(query);
        const url = `https://quark.sm.cn/s?q=${encodedQuery}&page=${page}`;
        return performSearch('quark', url, includeLinks);
    }
    async function search_bing_images(query) {
        const encodedQuery = encodeURIComponent(query);
        const url = `https://www.bing.com/images/search?q=${encodedQuery}`;
        return performImageSearch('bing_images', url);
    }
    async function search_wikimedia_images(query) {
        const encodedQuery = encodeURIComponent(query);
        const url = `https://commons.wikimedia.org/wiki/Special:MediaSearch?type=image&search=${encodedQuery}`;
        return performImageSearch('wikimedia_images', url);
    }
    async function search_duckduckgo_images(query) {
        const encodedQuery = encodeURIComponent(query);
        const url = `https://duckduckgo.com/?q=${encodedQuery}&iax=images&ia=images`;
        return performImageSearch('duckduckgo_images', url);
    }
    async function search_ecosia_images(query) {
        const encodedQuery = encodeURIComponent(query);
        const url = `https://www.ecosia.org/images?q=${encodedQuery}`;
        return performImageSearch('ecosia_images', url);
    }
    async function search_pexels_images(query) {
        const encodedQuery = encodeURIComponent(query);
        const url = `https://www.pexels.com/search/${encodedQuery}/`;
        return performImageSearch('pexels_images', url);
    }
    async function search_pixabay_images(query) {
        const encodedQuery = encodeURIComponent(query);
        const url = `https://pixabay.com/images/search/${encodedQuery}/`;
        return performImageSearch('pixabay_images', url);
    }
    const searchFunctions = {
        bing: search_bing,
        baidu: search_baidu,
        sogou: search_sogou,
        quark: search_quark
    };
    async function combined_search(query, platforms, includeLinks = true) {
        const platformKeysRaw = platforms.split(',');
        const platformKeys = [];
        for (const platform of platformKeysRaw) {
            const trimmedPlatform = platform.trim();
            if (trimmedPlatform) {
                platformKeys.push(trimmedPlatform);
            }
        }
        const searchPromises = [];
        for (const platform of platformKeys) {
            const searchFn = searchFunctions[platform];
            if (searchFn) {
                if (platform === 'bing') {
                    searchPromises.push(searchFn(query, includeLinks));
                }
                else {
                    // 注意：这里我们假设组合搜索总是从第一页开始
                    searchPromises.push(searchFn(query, '1', includeLinks));
                }
            }
            else {
                searchPromises.push(Promise.resolve({ platform, success: false, message: `不支持的搜索平台: ${platform}` }));
            }
        }
        return Promise.all(searchPromises);
    }
    async function search(query, platforms, includeLinks = false) {
        return combined_search(query, platforms || "bing,baidu,sogou,quark", includeLinks);
    }
    async function search_web(query, platforms, includeLinks = false) {
        return search(query, platforms, includeLinks);
    }
    async function main() {
        const result = await combined_search('如何学习编程', 'bing,baidu,sogou,quark');
        console.log(JSON.stringify(result, null, 2));
    }
    function wrap(coreFunction, parameterNames) {
        return async (params) => {
            const args = parameterNames.map((name) => params[name]);
            return coreFunction(...args);
        };
    }
    return {
        search_bing,
        search_baidu,
        search_sogou,
        search_quark,
        search,
        search_web,
        search_bing_images,
        search_wikimedia_images,
        search_duckduckgo_images,
        search_ecosia_images,
        search_pexels_images,
        search_pixabay_images,
        combined_search,
        wrap,
        main
    };
})();
exports.search_bing = various_search.wrap(various_search.search_bing, ['query', 'includeLinks']);
exports.search_baidu = various_search.wrap(various_search.search_baidu, ['query', 'page', 'includeLinks']);
exports.search_sogou = various_search.wrap(various_search.search_sogou, ['query', 'page', 'includeLinks']);
exports.search_quark = various_search.wrap(various_search.search_quark, ['query', 'page', 'includeLinks']);
exports.search = various_search.wrap(various_search.search, ['query', 'platforms', 'includeLinks']);
exports.search_web = various_search.wrap(various_search.search_web, ['query', 'platforms', 'includeLinks']);
exports.search_bing_images = various_search.wrap(various_search.search_bing_images, ['query']);
exports.search_wikimedia_images = various_search.wrap(various_search.search_wikimedia_images, ['query']);
exports.search_duckduckgo_images = various_search.wrap(various_search.search_duckduckgo_images, ['query']);
exports.search_ecosia_images = various_search.wrap(various_search.search_ecosia_images, ['query']);
exports.search_pexels_images = various_search.wrap(various_search.search_pexels_images, ['query']);
exports.search_pixabay_images = various_search.wrap(various_search.search_pixabay_images, ['query']);
exports.combined_search = various_search.wrap(various_search.combined_search, ['query', 'platforms', 'includeLinks']);
exports.main = various_search.main;
