#!/usr/bin/env node
/**
 * v5.3.17 ★ mark.html 本地化后的端到端渲染验证
 *
 * 为什么要这个脚本：
 *   CI 只编译 Kotlin/Java，**从不执行 WebView 里的 JS**。而 v5.3.15/5.3.16 两次
 *   公式缺陷（\xRightarrow 静默 0×0、\ \ /\dfrac13 解析失败）都发生在这一侧，
 *   每次都是用户装上手机才发现。这个脚本用 node 加载**真实打包进 assets 的文件**
 *   （markdown-it + katex + math-plugin + js-base64），把 mark.html 的两段逻辑
 *   （markdown-it 渲染 → 提取公式 → katex.render）完整跑一遍。
 *
 * 用法：node tools/test-mark-render.js
 * 退出码：0 全部通过；1 有失败（可直接进 CI）
 */
const fs = require('fs');
const path = require('path');

const assets = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'html');
const MarkdownIt = require(path.join(assets, 'vendor', 'markdown-it', 'markdown-it.min.js'));
const katex = require(path.join(assets, 'vendor', 'katex', 'katex.min.js'));
const Base64 = require(path.join(assets, 'vendor', 'js-base64', 'base64.min.js')).Base64;
const mathPlugin = require(path.join(assets, 'vendor', 'math-plugin.js'));

let pass = 0;
let fail = 0;
const failures = [];

function check(name, fn) {
    try {
        fn();
        pass++;
        console.log('  ✓ ' + name);
    } catch (e) {
        fail++;
        failures.push(name + ' → ' + e.message);
        console.log('  ✗ ' + name + ' → ' + e.message);
    }
}

function assert(cond, msg) {
    if (!cond) throw new Error(msg || 'assertion failed');
}

// ── 与 mark.html 完全一致的配置 ──
const md = new MarkdownIt({
    html: true,
    xhtmlOut: false,
    breaks: false,
    langPrefix: 'language-',
    linkify: true,
    typographer: true,
    quotes: '“”‘’',
    highlight: function (str, lang) {
        return '<pre class="hljs"><code>' + md.utils.escapeHtml(str) + '</code></pre>';
    },
});
md.use(mathPlugin);

/** 与 mark.html 后处理段同款：剥离 pdfLaTeX 间距参数 */
function normalizeLatex(src) {
    return src.replace(/\\\[\s*[\d.]+\s*(?:pt|mm|cm|in|ex|em|mu)\s*\]/g, '\\');
}

/** 从渲染出的 HTML 里取出公式占位，并按 mark.html 的方式真渲染 */
function renderMath(html) {
    const results = { inline: [], block: [] };
    const blockRe = /<span class="math block">([\s\S]*?)<\/span>/g;
    const inlineRe = /<span class="math inline">([\s\S]*?)<\/span>/g;
    let m;
    while ((m = blockRe.exec(html)) !== null) {
        const src = normalizeLatex(unescapeHtml(m[1]));
        // 与浏览器同款：throwOnError: true，失败必须能被发现
        results.block.push({ src: src, html: katex.renderToString(src, { displayMode: true, throwOnError: true }) });
    }
    while ((m = inlineRe.exec(html)) !== null) {
        const src = normalizeLatex(unescapeHtml(m[1]));
        results.inline.push({ src: src, html: katex.renderToString(src, { throwOnError: true }) });
    }
    return results;
}

function unescapeHtml(s) {
    return s.replace(/&lt;/g, '<').replace(/&gt;/g, '>')
        .replace(/&quot;/g, '"').replace(/&#x27;/g, "'").replace(/&amp;/g, '&');
}

function oneShot(markdown) {
    const html = md.render(markdown);
    return { html: html, math: renderMath(html) };
}

console.log('\n=== 1. v5.3.15 用户实测 9 条（双线伸展箭头族）===');
{
    const cases = [
        ['1 行内 xRightarrow', '$a \\xRightarrow{b} c$', 'inline'],
        ['2 块级 xRightarrow', '$$a \\xRightarrow{b} c$$', 'block'],
        ['3 块级 xrightarrow', '$$a \\xrightarrow{b} c$$', 'block'],
        ['4 块级 stackrel+Rightarrow', '$$a \\stackrel{b}{\\Rightarrow} c$$', 'block'],
        ['5 块级裸 Rightarrow', '$$a \\Rightarrow b$$', 'block'],
        ['6 块级 underbrace', '$$\\underbrace{x+y}_{z}$$', 'block'],
        ['7 块级 text 中文', '$$\\text{全部情况，逐格}$$', 'block'],
        ['8 块级长公式', '$$\\frac{a}{b}\\Rightarrow\\frac{c}{d}\\Rightarrow\\frac{e}{f}\\Rightarrow\\frac{g}{h}$$', 'block'],
    ];
    for (const [name, src, kind] of cases) {
        check(name, () => {
            const r = oneShot(src);
            const bucket = kind === 'block' ? r.math.block : r.math.inline;
            assert(bucket.length === 1, '应产出 1 个 ' + kind + ' 公式，实际 ' + bucket.length);
            assert(bucket[0].html.indexOf('katex') >= 0, 'katex 未产出内容');
        });
    }
    check('9 行内+块级含 xRightarrow 均不空白', () => {
        const a = oneShot('$a \\xRightarrow{b} c$');
        const b = oneShot('$$a \\xRightarrow{b} c$$');
        assert(a.math.inline.length === 1 && a.html.indexOf('[Latex]') < 0, '行内未渲染');
        assert(b.math.block.length === 1 && b.html.indexOf('[Latex]') < 0, '块级未渲染');
    });
}

console.log('\n=== 2. v5.3.16 用户两条 cases 公式（控制空格 + dfrac 单 token）===');
const CASES_1 = `$$
F(x,y)=
\\begin{cases}
0, & x<1 \\ \\text{或}\\ y<1, \\\\[4pt]
0, & 1\\le x<2,\\ 1\\le y<2, \\\\[4pt]
\\dfrac13, & 1\\le x<2,\\ y\\ge 2, \\\\[4pt]
\\dfrac13, & x\\ge 2,\\ 1\\le y<2, \\\\[4pt]
1, & x\\ge 2,\\ y\\ge 2.
\\end{cases}
$$`;
const CASES_2 = CASES_1.replace('0, & 1\\le x<2,\\ 1\\le y<2', '\\dfrac19, & 1\\le x<2,\\ 1\\le y<2');
{
    check('cases 公式 1 可渲染', () => {
        const r = oneShot(CASES_1);
        assert(r.math.block.length === 1, '应产出 1 个块级公式');
        assert(r.math.block[0].src.indexOf('\\begin{cases}') >= 0, 'cases 环境丢失');
        assert(r.math.block[0].src.indexOf('\\ [4pt]') < 0, '间距参数未剥离');
    });
    check('cases 公式 2（dfrac19 变体）可渲染', () => {
        const r = oneShot(CASES_2);
        assert(r.math.block.length === 1, '应产出 1 个块级公式');
        assert(r.math.block[0].src.indexOf('\\dfrac19') >= 0, 'dfrac19 内容丢失');
    });
    check('cases 渲染保留中文与坐标系', () => {
        const r = oneShot(CASES_1);
        assert(r.math.block[0].src.indexOf('或') >= 0, '中文丢失');
        assert(r.math.block[0].src.indexOf('F(x,y)') >= 0, '正文丢失');
    });
}

console.log('\n=== 3. 其他 Markdown 能力未被本地化破坏 ===');
{
    check('表格', () => {
        const r = oneShot('| a | b |\n|---|---|\n| 1 | 2 |');
        assert(r.html.indexOf('<table>') >= 0, '表格未渲染');
        assert(r.html.indexOf('<td>1</td>') >= 0, '单元格未渲染');
    });
    check('代码块（fence 不被 $$ 规则干扰）', () => {
        const r = oneShot('```\nconst a = 1;\n```');
        assert(r.html.indexOf('<pre') >= 0, '代码块未渲染');
        assert(r.math.block.length === 0, '代码块被误判为公式');
    });
    check('行内代码里的 $ 不被当公式', () => {
        const r = oneShot('价格 `$100` 元');
        assert(r.math.inline.length === 0, '行内代码里的 $ 被误判');
        assert(r.html.indexOf('<code>$100</code>') >= 0, '行内代码未渲染');
    });
    check('普通美元金额不被当公式', () => {
        const r = oneShot('价格 $ 100 元');
        assert(r.math.inline.length === 0, '裸美元被误判为公式');
    });
    check('任务列表插件可用', () => {
        const taskLists = require(path.join(assets, 'vendor', 'markdown-it-task-lists.js'));
        const md2 = new MarkdownIt({ html: true });
        md2.use(mathPlugin);
        md2.use(taskLists, { enabled: true, label: true });
        const html = md2.render('- [ ] 待办\n- [x] 已完成');
        assert(html.indexOf('type="checkbox"') >= 0 || html.indexOf('task-list') >= 0, '任务列表未生效');
    });
    check('Base64 解码可用（mark.html 的内容入口）', () => {
        const payload = '你好 **world**';
        const encoded = Base64.encode(payload);
        assert(Base64.decode(encoded) === payload, 'Base64 往返失败');
    });
    check('\\[ ... \\] 形式', () => {
        const r = oneShot('\\[a \\Rightarrow b\\]');
        assert(r.math.block.length === 1, '\\[ ... \\] 未识别');
    });
    check('\\( ... \\) 形式', () => {
        const r = oneShot('\\(a+b\\)');
        assert(r.math.inline.length === 1, '\\( ... \\) 未识别');
    });
}

console.log('\n=== 4. 本地资源完整性 ===');
{
    check('katex.min.js 存在且可调用', () => {
        assert(typeof katex.renderToString === 'function', 'katex 不可用');
        assert(katex.renderToString('x', { throwOnError: true }).indexOf('katex') >= 0, 'katex 输出异常');
    });
    check('katex CSS 引用本地字体', () => {
        const css = fs.readFileSync(path.join(assets, 'vendor', 'katex', 'katex.min.css'), 'utf8');
        assert(css.indexOf('fonts/KaTeX_') >= 0, 'CSS 未引用本地字体');
        assert(css.indexOf('http') < 0, 'CSS 仍含远程地址');
    });
    const fonts = fs.readdirSync(path.join(assets, 'vendor', 'katex', 'fonts'));
    check('字体文件齐全（' + fonts.length + ' 个 woff2）', () => {
        assert(fonts.length >= 10, '字体过少: ' + fonts.length);
        assert(fonts.every(f => f.endsWith('.woff2')), '存在非 woff2 文件');
    });
    check('mark.html 零远程依赖（只看真实资源引用，注释不算）', () => {
        const html = fs.readFileSync(path.join(assets, 'mark.html'), 'utf8');
        // 只检查真正会发请求的地方：script/link 的 src/href、以及 JS import 语句
        const refs = [
            ...[...html.matchAll(/<script[^>]+src="([^"]+)"/g)].map(m => m[1]),
            ...[...html.matchAll(/<link[^>]+href="([^"]+)"/g)].map(m => m[1]),
        ];
        for (const ref of refs) {
            assert(ref.indexOf('http') !== 0 && ref.indexOf('//') !== 0,
                '远程资源引用: ' + ref);
        }
        assert(!/^\s*import\s.+from\s+['"]https?:/m.test(html), '仍有远程 import');
    });
    check('mark.html 的全部 script src 都指向存在的本地文件', () => {
        const html = fs.readFileSync(path.join(assets, 'mark.html'), 'utf8');
        const srcs = [...html.matchAll(/<script src="([^"]+)"/g)].map(m => m[1]);
        assert(srcs.length >= 7, 'script 数量异常: ' + srcs.length);
        for (const src of srcs) {
            const p = path.join(assets, src.replace('./', ''));
            assert(fs.existsSync(p), '缺失文件: ' + src);
        }
    });
    check('mark.html 的两个 CSS link 都指向存在的本地文件', () => {
        const html = fs.readFileSync(path.join(assets, 'mark.html'), 'utf8');
        const hrefs = [...html.matchAll(/<link[^>]+href="([^"]+)"/g)].map(m => m[1]);
        for (const href of hrefs) {
            if (href.indexOf('http') === 0) throw new Error('远程 CSS: ' + href);
            const p = path.join(assets, href.replace('./', ''));
            assert(fs.existsSync(p), '缺失 CSS: ' + href);
        }
    });
}

console.log('\n──────────────────────────────────────');
console.log('通过 ' + pass + ' / 失败 ' + fail);
if (fail > 0) {
    console.log('\n失败清单：');
    failures.forEach(f => console.log('  · ' + f));
    process.exit(1);
}
console.log('MARK-RENDER-OK ✅');
