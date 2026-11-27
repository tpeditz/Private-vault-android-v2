/* Progressive enhancement only; no network calls, analytics, or dependencies. */
(() => {
  "use strict";
  document.documentElement.classList.add("js");
  const nav = document.querySelector(".main-nav");
  const toggle = document.querySelector(".menu-toggle");
  const mobile = window.matchMedia("(max-width: 600px)");
  function setMenu(open) {
    if (!nav || !toggle) return;
    nav.classList.toggle("is-open", open);
    toggle.setAttribute("aria-expanded", String(open));
    toggle.setAttribute("aria-label", open ? "Close navigation" : "Open navigation");
  }
  if (nav && toggle) {
    toggle.addEventListener("click", () => setMenu(toggle.getAttribute("aria-expanded") !== "true"));
    nav.addEventListener("click", e => { if (e.target.closest("a")) setMenu(false); });
    document.addEventListener("keydown", e => {
      if (e.key === "Escape" && toggle.getAttribute("aria-expanded") === "true") {
        setMenu(false); toggle.focus();
      }
    });
    document.addEventListener("click", e => {
      if (!nav.contains(e.target) && !toggle.contains(e.target)) setMenu(false);
    });
    if (mobile.addEventListener) mobile.addEventListener("change", () => setMenu(false));
  }
  const explorer = document.querySelector("[data-feature-explorer]");
  if (explorer) {
    const list = explorer.querySelector(".feature-tabs");
    const tabs = Array.from(explorer.querySelectorAll("[data-tab]"));
    const panels = Array.from(explorer.querySelectorAll("[data-panel]"));
    list.setAttribute("role", "tablist");
    list.setAttribute("aria-orientation", mobile.matches ? "horizontal" : "vertical");
    if (mobile.addEventListener) mobile.addEventListener("change", () => list.setAttribute("aria-orientation", mobile.matches ? "horizontal" : "vertical"));
    tabs.forEach(t => {
      t.setAttribute("role", "tab");
      t.setAttribute("aria-controls", "panel-" + t.dataset.tab);
    });
    panels.forEach(p => {
      p.setAttribute("role", "tabpanel");
      p.setAttribute("aria-labelledby", "tab-" + p.dataset.panel);
      p.tabIndex = 0;
    });
    function select(index, focus = false) {
      const tab = tabs[index];
      if (!tab) return;
      tabs.forEach(t => {
        const active = t === tab;
        t.classList.toggle("is-selected", active);
        t.setAttribute("aria-selected", String(active));
        t.tabIndex = active ? 0 : -1;
      });
      panels.forEach(p => {
        const active = p.dataset.panel === tab.dataset.tab;
        p.hidden = !active;
        p.classList.remove("panel-enter");
        if (active) p.classList.add("panel-enter");
      });
      if (focus) tab.focus();
    }
    tabs.forEach((t, i) => {
      t.addEventListener("click", () => select(i));
      t.addEventListener("keydown", e => {
        let next;
        if (e.key === "ArrowDown" || e.key === "ArrowRight") next = (i + 1) % tabs.length;
        else if (e.key === "ArrowUp" || e.key === "ArrowLeft") next = (i - 1 + tabs.length) % tabs.length;
        else if (e.key === "Home") next = 0;
        else if (e.key === "End") next = tabs.length - 1;
        else return;
        e.preventDefault(); select(next, true);
      });
    });
    select(0);
  }
  const reduce = window.matchMedia("(prefers-reduced-motion: reduce)");
  if (!reduce.matches && "IntersectionObserver" in window) {
    const items = document.querySelectorAll(".reveal");
    const observer = new IntersectionObserver(entries => {
      entries.forEach(entry => {
        if (entry.isIntersecting) {
          entry.target.classList.add("is-visible");
          observer.unobserve(entry.target);
        }
      });
    }, { threshold: 0.07, rootMargin: "0px 0px -18px 0px" });
    document.documentElement.classList.add("motion-ready");
    items.forEach(item => {
      item.addEventListener("focusin", () => {
        item.classList.add("is-visible"); observer.unobserve(item);
      });
      observer.observe(item);
    });
  }
  const progress = document.querySelector(".reading-progress");
  if (progress) {
    let queued = false;
    const draw = () => {
      const extent = document.documentElement.scrollHeight - window.innerHeight;
      progress.style.transform = "scaleX(" + (extent > 0 ? Math.max(0, Math.min(1, window.scrollY / extent)) : 0) + ")";
      queued = false;
    };
    const schedule = () => { if (!queued) { queued = true; window.requestAnimationFrame(draw); } };
    window.addEventListener("scroll", schedule, { passive: true });
    window.addEventListener("resize", schedule);
    draw();
  }
})();
