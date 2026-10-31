/**
 * v5.3.17 ★ markdown-it math 插件（本地化替代 @vscode/markdown-it-katex）
 *
 * 只做「占位」：把公式包成 <span class="math inline|block">原式</span>，
 * 真正的 katex.render 由 mark.html 的后处理段按 .math.inline / .math.block 执行。
 *
 * 支持：$...$ / $$...$$ / \(...\) / \[...\]
 * 边界：
 *   - 行内代码（反引号）内的 $ 不会被误判（本规则排在 backticks 之后）
 *   - fenced code block 内的 $ 不会被误判（块级规则在 fence 之前，但 fence 优先整体消费）
 *   - 行内公式不跨行；空内容不匹配
 *   - \$ 转义不受影响（escape 规则在行内规则之前）
 *
 * 两种加载方式都支持：
 *   - 浏览器：<script src=".../math-plugin.js"> 后使用全局 mathPlugin
 *   - Node：require('...') 直接得到函数（用于单测）
 */
(function (root, factory) {
    if (typeof module === 'object' && module.exports) {
        module.exports = factory();
    } else {
        root.mathPlugin = factory();
    }
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
    'use strict';

    var BACKSLASH = String.fromCharCode(92); // 反斜杠，避免本文件自身的转义歧义
    var NEWLINE = String.fromCharCode(10);

    function startsWithAt(s, needle, at) {
        return s.length >= at + needle.length && s.slice(at, at + needle.length) === needle;
    }

    return function mathPlugin(md) {
        var esc = md.utils.escapeHtml;

        function mathInline(state, silent) {
            var src = state.src;
            var start = state.pos;

            // $$ 交给块级规则
            if (startsWithAt(src, '$$', start)) return false;
            // 本规则排在 escape 之前（否则 \( 会被 escape 吃掉成 "("），
            // 所以必须自己让出 \$ —— 反斜杠转义的美元交给 escape 处理成字面 $。
            if (start > 0 && src.charAt(start - 1) === BACKSLASH) return false;

            var openLen;
            var close;
            if (src.charAt(start) === '$') {
                openLen = 1;
                close = '$';
            } else if (startsWithAt(src, BACKSLASH + '(', start)) {
                openLen = 2;
                close = BACKSLASH + ')';
            } else {
                return false;
            }

            var pos = start + openLen;
            while (pos < state.posMax) {
                // ★必须先判闭合符：\) 本身以反斜杠开头，若先走下面的「转义跳过」
                // 分支就会把闭合符连同后面一个字符一起吞掉 → \(...\) 永远匹配不到
                // （v5.3.17 实测：\(a+b\) 渲染成了纯文本，就是这一行顺序写反）。
                if (close !== '$' && startsWithAt(src, close, pos)) break;
                var ch = src.charAt(pos);
                if (ch === BACKSLASH) {
                    pos += 2;
                    continue;
                }
                if (ch === NEWLINE) return false;
                if (close === '$' && ch === '$') break;
                pos++;
            }
            if (pos >= state.posMax) return false;

            var content = src.slice(start + openLen, pos);
            if (!content.trim()) return false;

            if (!silent) {
                state.push('math_inline', 'span', 0).content = content;
            }
            state.pos = pos + close.length;
            return true;
        }

        function mathBlock(state, startLine, endLine, silent) {
            var pos = state.bMarks[startLine] + state.tShift[startLine];
            var max = state.eMarks[startLine];
            var src = state.src;

            var openLen;
            var close;
            if (startsWithAt(src, '$$', pos)) {
                openLen = 2;
                close = '$$';
            } else if (startsWithAt(src, BACKSLASH + '[', pos)) {
                openLen = 2;
                close = BACKSLASH + ']';
            } else {
                return false;
            }

            var afterOpen = src.slice(pos + openLen, max);

            // 同一行闭合：$$ a \Rightarrow b $$
            var sameLine = afterOpen.lastIndexOf(close);
            if (sameLine >= 0) {
                var inlineContent = afterOpen.slice(0, sameLine);
                if (!inlineContent.trim()) return false;
                if (!silent) {
                    state.push('math_block', 'span', 0).content = inlineContent;
                }
                state.line = startLine + 1;
                return true;
            }

            // 跨行：逐行寻找结束标记
            var lines = [];
            if (afterOpen.trim()) lines.push(afterOpen);

            var nextLine = startLine + 1;
            var found = false;
            for (; nextLine < endLine; nextLine++) {
                var ls = state.bMarks[nextLine] + state.tShift[nextLine];
                var le = state.eMarks[nextLine];
                var lineText = src.slice(ls, le);
                var idx = lineText.indexOf(close);
                if (idx >= 0) {
                    lines.push(lineText.slice(0, idx));
                    found = true;
                    break;
                }
                lines.push(lineText);
            }
            if (!found) return false;

            var content = lines.join(NEWLINE);
            if (!content.trim()) return false;
            if (!silent) {
                state.push('math_block', 'span', 0).content = content;
            }
            state.line = nextLine + 1;
            return true;
        }

        // v5.3.17：必须排在 escape **之前** —— markdown-it 的 escape 会把 \( 消耗成
        // 字面 "("（"(" 是 ASCII 标点），排在它后面就永远看不到 \( 了。
        // 代价是 \$ 转义要自己让出（见 mathInline 开头的判断），行内代码仍安全：
        // backticks 规则优先级更高，反引号内部的内容不会进入本规则。
        md.inline.ruler.before('escape', 'math_inline', mathInline);
        // 块级规则排在 fence 之前 → $$ 块优先于代码块判定
        md.block.ruler.before('fence', 'math_block', mathBlock, {
            alt: ['paragraph', 'reference', 'blockquote', 'list'],
        });

        md.renderer.rules.math_inline = function (tokens, idx) {
            return '<span class="math inline">' + esc(tokens[idx].content) + '</span>';
        };
        md.renderer.rules.math_block = function (tokens, idx) {
            return '<span class="math block">' + esc(tokens[idx].content) + '</span>';
        };
    };
});
