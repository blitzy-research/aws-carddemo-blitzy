/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 */

/*
 * A RENDERED DIAGRAM MUST NOT BE NARROWER THAN THE DRAWING INSIDE IT.
 *
 * Mermaid 10.4.0's pie renderer takes the width of its own coordinate system from the width of the
 * element it is being drawn into, and then lays the legend out at a fixed distance to the right of the
 * circle:
 *
 *     const r = 450;
 *     const u = document.getElementById(c)?.parentElement?.offsetWidth ?? h.useWidth;
 *     p.attr("viewBox", `0 0 ${u} ${r}`);
 *
 * The circle shrinks with u. The legend does not: its distance from the circle is a constant and its
 * width comes from the label text. So below roughly 790 units the legend is laid out beyond the right
 * edge of the very viewBox that was derived from u, and an SVG root element clips its own overflow by
 * default - `svg:root { overflow: hidden }` is in the UA stylesheet, and it takes effect before any
 * `overflow` on the container is consulted. The result is not a diagram that looks cramped; it is a
 * diagram whose key is deleted. On this documentation set, at a 390px viewport, all ten legend labels of
 * "Completed Work Distribution" begin 59px past the clip edge and paint nothing at all, and the chart
 * title loses a character at each end - ten colour-coded wedges with percentages and no way to tell what
 * any of them means, with no scrollbar, no pan and no page-level scroll to recover them with.
 *
 * Nothing in Mermaid's configuration reaches this. `pie.useWidth` is only the `??` fallback for a missing
 * parent element, so it is unreachable whenever the diagram renders at all; `pie.useMaxWidth` changes the
 * width and max-width attributes and leaves the viewBox exactly as it was; and no font-size setting helps,
 * because at that width the legend's starting x already exceeds the viewBox before a single glyph is
 * measured. Nor can CSS repair it: a viewBox is not a CSS property, and giving the element a min-width
 * merely scales the same too-small coordinate system up, clip included.
 *
 * So the viewBox is corrected here, after the draw, from the geometry Mermaid actually produced.
 * getBBox() reports the union of everything drawn in user space - circle, slice labels, legend and title
 * alike, including the parts outside the viewBox - and the viewBox is grown to contain it.
 *
 * THE FILENAME IS LOAD-BEARING AND MUST NOT CONTAIN THE WORD "MERMAID". mkdocs-mermaid2-plugin scans
 * extra_javascript for the first entry whose basename contains "mermaid", case-insensitively, and treats
 * that file as the Mermaid library itself - it then emits it in place of the CDN import and follows it with
 * a bare mermaid.initialize({}). An earlier spelling of this file was named mermaid-viewbox-fit.js, and the
 * published pages lost `import mermaid from ".../mermaid.esm.min.mjs"` altogether, leaving initialize() to
 * run against an undefined global and every diagram on the site unrendered. The build still exited 0; only
 * the emitted markup gave it away.
 *
 * IT ONLY EVER GROWS. That is the whole of the safety argument, and it is why this runs against every
 * diagram on the site rather than trying to identify pie charts: where the drawing already fits, the union
 * is the viewBox itself and nothing is written, so the five flowchart and entity-relationship diagrams and
 * every pie at desktop width render byte-identically to how they render without this file. Growing the box
 * also cannot introduce a scrollbar or shift the page, because the element's width is unchanged - a wider
 * coordinate system inside the same box simply scales the drawing down to fit, which is the behaviour a
 * reader wants: complete and smaller, never cropped.
 */
(function () {
  "use strict";

  /* Enough to keep glyph antialiasing off the boundary, in user units. */
  var PAD = 8;
  /* Ignore differences too small to be visible, so a rounding artefact never rewrites an attribute. */
  var EPSILON = 0.5;

  function fit(svg) {
    if (svg.hasAttribute("data-viewbox-fitted")) {
      return;
    }

    var viewBox = svg.viewBox;
    if (!viewBox || !viewBox.baseVal) {
      return;
    }
    var base = viewBox.baseVal;
    if (!base.width || !base.height) {
      return;
    }

    var box;
    try {
      box = svg.getBBox();
    } catch (error) {
      /* getBBox throws on an element with no layout box yet; a later pass will catch it. */
      return;
    }
    if (!box || (!box.width && !box.height)) {
      return;
    }

    /* Mark before writing: whatever the outcome, this element has now been assessed, and the attribute
       write below would otherwise re-trigger the observer that called us. */
    svg.setAttribute("data-viewbox-fitted", "true");

    var minX = Math.min(base.x, box.x - PAD);
    var minY = Math.min(base.y, box.y - PAD);
    var maxX = Math.max(base.x + base.width, box.x + box.width + PAD);
    var maxY = Math.max(base.y + base.height, box.y + box.height + PAD);

    var grewHorizontally = base.x - minX > EPSILON || maxX - (base.x + base.width) > EPSILON;
    var grewVertically = base.y - minY > EPSILON || maxY - (base.y + base.height) > EPSILON;
    if (!grewHorizontally && !grewVertically) {
      return;
    }

    svg.setAttribute("viewBox", minX + " " + minY + " " + (maxX - minX) + " " + (maxY - minY));
  }

  function scan() {
    var rendered = document.querySelectorAll('div.mermaid[data-processed="true"] > svg');
    for (var i = 0; i < rendered.length; i += 1) {
      fit(rendered[i]);
    }
  }

  /* Mermaid renders asynchronously from an ES module at the end of the body, so the diagrams are not in
     the DOM when this file runs. data-processed lands on the div.mermaid wrapper - not on the svg, which
     is worth stating because the obvious selector svg[data-processed] matches nothing on this version -
     and that attribute is the signal a diagram is complete. */
  if (typeof MutationObserver === "function") {
    new MutationObserver(scan).observe(document.documentElement, {
      subtree: true,
      childList: true,
      attributes: true,
      attributeFilter: ["data-processed"]
    });
  }

  /* Fonts load after the first paint, and a wider glyph set widens the legend, so re-assess once the
     text metrics are final. The marker attribute is cleared first so the second pass is not a no-op. */
  if (document.fonts && typeof document.fonts.ready === "object") {
    document.fonts.ready.then(function () {
      var fitted = document.querySelectorAll("svg[data-viewbox-fitted]");
      for (var i = 0; i < fitted.length; i += 1) {
        fitted[i].removeAttribute("data-viewbox-fitted");
      }
      scan();
    });
  }

  document.addEventListener("DOMContentLoaded", scan);
  window.addEventListener("load", scan);
  scan();
})();
