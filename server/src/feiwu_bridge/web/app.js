const $ = (selector) => document.querySelector(selector);
const loginLayer = $("#loginLayer");
const loginForm = $("#loginForm");
const configForm = $("#configForm");
const toast = $("#toast");
let adminToken = sessionStorage.getItem("feiwuAdminToken") || "";
let toastTimer;
let startupTimers = [];
let bootFrame = 0;
let bootAudioContext;
let bootAudioSources = [];
let bootAudioGraph;
let soundEnabled = localStorage.getItem("feiwuBootSound") !== "off";

function notify(message) {
  toast.textContent = message;
  toast.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.remove("show"), 3200);
}

function clearStartupTimers() {
  startupTimers.forEach(clearTimeout);
  startupTimers = [];
  cancelAnimationFrame(bootFrame);
  bootFrame = 0;
}

function stopBootAudio() {
  bootAudioSources.forEach((source) => {
    try { source.stop(); } catch { /* source already stopped */ }
  });
  bootAudioSources = [];
  if (bootAudioGraph) {
    try { bootAudioGraph.compressor.disconnect(); } catch { /* graph already disconnected */ }
    bootAudioGraph = null;
  }
}

function syncSoundControls() {
  [["#startupSound", "音效 开", "音效 关"], ["#toggleBootSound", "启动音效：开", "启动音效：关"]]
    .forEach(([selector, onLabel, offLabel]) => {
      const button = $(selector);
      if (!button) return;
      button.setAttribute("aria-pressed", String(soundEnabled));
      if (selector === "#startupSound") {
        button.lastChild.textContent = soundEnabled ? onLabel : offLabel;
      } else {
        const label = button.querySelector(".action-label");
        if (label) label.textContent = soundEnabled ? onLabel : offLabel;
      }
    });
}

async function getBootAudioContext() {
  if (!bootAudioContext) {
    const AudioContextClass = window.AudioContext || window.webkitAudioContext;
    if (!AudioContextClass) return null;
    bootAudioContext = new AudioContextClass();
  }
  if (bootAudioContext.state === "suspended") await bootAudioContext.resume();
  return bootAudioContext;
}

function createBootAudioGraph(context) {
  const input = context.createGain();
  const dry = context.createGain();
  const reverb = context.createConvolver();
  const wet = context.createGain();
  const master = context.createGain();
  const compressor = context.createDynamicsCompressor();
  const impulseLength = Math.ceil(context.sampleRate * 1.45);
  const impulse = context.createBuffer(2, impulseLength, context.sampleRate);

  for (let channel = 0; channel < impulse.numberOfChannels; channel += 1) {
    const data = impulse.getChannelData(channel);
    for (let index = 0; index < data.length; index += 1) {
      const decay = Math.pow(1 - index / data.length, 3.4);
      data[index] = (Math.random() * 2 - 1) * decay;
    }
  }

  reverb.buffer = impulse;
  dry.gain.value = .92;
  wet.gain.value = .16;
  master.gain.value = .72;
  compressor.threshold.value = -18;
  compressor.knee.value = 12;
  compressor.ratio.value = 5;
  compressor.attack.value = .004;
  compressor.release.value = .2;

  input.connect(dry).connect(master);
  input.connect(reverb).connect(wet).connect(master);
  master.connect(compressor).connect(context.destination);
  return { input, compressor };
}

function connectBootVoice(context, source, output, graph, start, duration, panFrom = 0, panTo = panFrom) {
  if (context.createStereoPanner) {
    const panner = context.createStereoPanner();
    panner.pan.setValueAtTime(Math.max(-1, Math.min(1, panFrom)), start);
    panner.pan.linearRampToValueAtTime(Math.max(-1, Math.min(1, panTo)), start + duration);
    source.connect(output).connect(panner).connect(graph.input);
  } else {
    source.connect(output).connect(graph.input);
  }
}

function bootTone(context, graph, options) {
  const {
    start, duration, from, to = from, volume, type = "sine", attack = .025,
    release = .16, panFrom = 0, panTo = panFrom, filterFrom = 0, filterTo = filterFrom,
    detune = 0,
  } = options;
  const oscillator = context.createOscillator();
  const gain = context.createGain();
  oscillator.type = type;
  oscillator.detune.value = detune;
  oscillator.frequency.setValueAtTime(Math.max(1, from), start);
  oscillator.frequency.exponentialRampToValueAtTime(Math.max(1, to), start + duration);
  gain.gain.setValueAtTime(.0001, start);
  gain.gain.exponentialRampToValueAtTime(volume, start + Math.min(attack, duration * .35));
  gain.gain.setValueAtTime(Math.max(.0001, volume * .72), start + Math.max(attack, duration - release));
  gain.gain.exponentialRampToValueAtTime(.0001, start + duration);

  if (filterFrom > 0) {
    const filter = context.createBiquadFilter();
    filter.type = "lowpass";
    filter.Q.value = 1.2;
    filter.frequency.setValueAtTime(filterFrom, start);
    filter.frequency.exponentialRampToValueAtTime(Math.max(1, filterTo), start + duration);
    oscillator.connect(filter);
    connectBootVoice(context, filter, gain, graph, start, duration, panFrom, panTo);
  } else {
    connectBootVoice(context, oscillator, gain, graph, start, duration, panFrom, panTo);
  }

  oscillator.start(start);
  oscillator.stop(start + duration + .03);
  bootAudioSources.push(oscillator);
}

function bootNoise(context, graph, options) {
  const {
    start, duration, volume, filterType = "bandpass", filterFrom = 400,
    filterTo = filterFrom, q = 1.4, attack = .025, release = .18,
    panFrom = 0, panTo = panFrom,
  } = options;
  const length = Math.ceil(context.sampleRate * duration);
  const buffer = context.createBuffer(1, length, context.sampleRate);
  const data = buffer.getChannelData(0);
  for (let index = 0; index < length; index += 1) data[index] = Math.random() * 2 - 1;
  const source = context.createBufferSource();
  const filter = context.createBiquadFilter();
  const gain = context.createGain();
  source.buffer = buffer;
  filter.type = filterType;
  filter.Q.value = q;
  filter.frequency.setValueAtTime(Math.max(1, filterFrom), start);
  filter.frequency.exponentialRampToValueAtTime(Math.max(1, filterTo), start + duration);
  gain.gain.setValueAtTime(.0001, start);
  gain.gain.exponentialRampToValueAtTime(volume, start + Math.min(attack, duration * .35));
  gain.gain.setValueAtTime(Math.max(.0001, volume * .62), start + Math.max(attack, duration - release));
  gain.gain.exponentialRampToValueAtTime(.0001, start + duration);
  source.connect(filter);
  connectBootVoice(context, filter, gain, graph, start, duration, panFrom, panTo);
  source.start(start);
  source.stop(start + duration + .02);
  bootAudioSources.push(source);
}

function bootDataBurst(context, graph, start, frequency, pan) {
  [0, .075, .15, .255].forEach((offset, index) => {
    bootTone(context, graph, {
      start: start + offset,
      duration: index === 3 ? .14 : .07,
      from: frequency * (1 + index * .18),
      to: frequency * (1.16 + index * .2),
      volume: index === 3 ? .035 : .024,
      type: index % 2 ? "triangle" : "sine",
      attack: .008,
      release: .05,
      panFrom: pan,
      panTo: Math.min(1, pan + .22),
    });
  });
}

async function playBootAudio() {
  if (!soundEnabled) return;
  const context = await getBootAudioContext();
  if (!context) return;
  stopBootAudio();
  const graph = createBootAudioGraph(context);
  bootAudioGraph = graph;
  const start = context.currentTime + .04;

  // Core ignition: a controlled sub layer and filtered air intake establish scale.
  bootTone(context, graph, {
    start, duration: 5.95, from: 46, to: 29, volume: .038, type: "sawtooth",
    attack: .5, release: .9, filterFrom: 150, filterTo: 72,
  });
  bootTone(context, graph, {
    start: start + .04, duration: 1.28, from: 58, to: 174, volume: .1,
    attack: .12, release: .3, type: "sine", filterFrom: 180, filterTo: 1100,
  });
  bootNoise(context, graph, {
    start: start + .02, duration: 1.34, volume: .04, filterFrom: 240, filterTo: 2600,
    q: .8, attack: .18, release: .4, panFrom: -.25, panTo: .25,
  });

  // Diagnostic scan: short positional pulses mirror the targeting rings.
  [
    [.72, 620, -0.72, -0.2],
    [1.56, 840, .65, .12],
  ].forEach(([offset, frequency, panFrom, panTo]) => {
    bootTone(context, graph, {
      start: start + offset, duration: .24, from: frequency, to: frequency * 1.7,
      volume: .055, type: "triangle", attack: .012, release: .13, panFrom, panTo,
    });
    bootNoise(context, graph, {
      start: start + offset, duration: .2, volume: .018, filterType: "highpass",
      filterFrom: 1700, filterTo: 5200, q: .7, attack: .008, release: .12,
      panFrom, panTo,
    });
  });
  bootDataBurst(context, graph, start + 1.72, 1160, -.32);

  // Relay acquisition: a wide rising sweep travels across the stereo field.
  bootNoise(context, graph, {
    start: start + 2.08, duration: 1.22, volume: .055, filterFrom: 180, filterTo: 4800,
    q: 2.4, attack: .18, release: .24, panFrom: -.9, panTo: .9,
  });
  bootTone(context, graph, {
    start: start + 2.34, duration: 1.08, from: 126, to: 980, volume: .05,
    type: "sawtooth", attack: .12, release: .22, filterFrom: 260, filterTo: 3400,
    panFrom: -.75, panTo: .75,
  });
  bootDataBurst(context, graph, start + 2.52, 720, -.5);

  // Provider lock: denser pulses create acceleration before the central impact.
  bootTone(context, graph, {
    start: start + 3.28, duration: .94, from: 92, to: 740, volume: .075,
    type: "triangle", attack: .08, release: .17, filterFrom: 190, filterTo: 2600,
  });
  bootDataBurst(context, graph, start + 3.4, 980, .22);
  bootDataBurst(context, graph, start + 3.8, 1320, -.12);

  // Core impact: transient crack, low-frequency drop, then a spacious resolved chord.
  bootNoise(context, graph, {
    start: start + 4.31, duration: .46, volume: .12, filterFrom: 280, filterTo: 92,
    q: .72, attack: .006, release: .38,
  });
  bootNoise(context, graph, {
    start: start + 4.31, duration: .22, volume: .05, filterType: "highpass",
    filterFrom: 2200, filterTo: 7200, q: .6, attack: .003, release: .18,
  });
  bootTone(context, graph, {
    start: start + 4.3, duration: .92, from: 92, to: 32, volume: .19,
    type: "sine", attack: .01, release: .68,
  });
  bootTone(context, graph, {
    start: start + 4.33, duration: .44, from: 1380, to: 310, volume: .048,
    type: "square", attack: .004, release: .32, filterFrom: 4200, filterTo: 900,
  });

  [73.42, 110, 164.81, 293.66].forEach((frequency, index) => {
    bootTone(context, graph, {
      start: start + 4.48 + index * .035,
      duration: 1.28,
      from: frequency,
      to: frequency * 1.015,
      volume: index === 0 ? .055 : .03,
      type: index < 2 ? "sine" : "triangle",
      attack: .14,
      release: .62,
      panFrom: (index - 1.5) * .28,
      panTo: (index - 1.5) * .18,
      detune: index % 2 ? 4 : -4,
    });
  });

  // Completion signature: bright but soft, leaving a clean tail as the UI appears.
  bootTone(context, graph, {
    start: start + 5.24, duration: .74, from: 660, to: 1320, volume: .043,
    type: "sine", attack: .025, release: .42, panFrom: -.2, panTo: .28,
  });
  bootTone(context, graph, {
    start: start + 5.39, duration: .66, from: 990, to: 1760, volume: .028,
    type: "triangle", attack: .02, release: .42, panFrom: .28, panTo: -.08,
  });
  bootNoise(context, graph, {
    start: start + 5.24, duration: .76, volume: .018, filterType: "highpass",
    filterFrom: 4200, filterTo: 8800, q: .8, attack: .06, release: .5,
    panFrom: -.4, panTo: .4,
  });
}

function startBootParticles(duration = 6600) {
  const canvas = $("#bootParticles");
  if (!canvas) return;
  const context = canvas.getContext("2d");
  const pixelRatio = Math.min(window.devicePixelRatio || 1, 2);
  const resize = () => {
    canvas.width = Math.floor(innerWidth * pixelRatio);
    canvas.height = Math.floor(innerHeight * pixelRatio);
    canvas.style.width = `${innerWidth}px`;
    canvas.style.height = `${innerHeight}px`;
    context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
  };
  resize();
  const particleCount = Math.min(190, Math.max(90, Math.round(innerWidth / 7)));
  const particles = Array.from({ length: particleCount }, () => ({
    angle: Math.random() * Math.PI * 2,
    radius: Math.max(innerWidth, innerHeight) * (.35 + Math.random() * .65),
    speed: .72 + Math.random() * .56,
    size: .4 + Math.random() * 1.6,
    drift: (Math.random() - .5) * .5,
  }));
  const startedAt = performance.now();
  const draw = (now) => {
    const elapsed = now - startedAt;
    const progress = Math.min(1, elapsed / duration);
    const centerX = innerWidth / 2;
    const centerY = innerHeight * .46;
    context.clearRect(0, 0, innerWidth, innerHeight);
    context.globalCompositeOperation = "lighter";
    particles.forEach((particle, index) => {
      const collapse = Math.max(.035, 1 - progress * 1.28 * particle.speed);
      const radius = particle.radius * collapse;
      const angle = particle.angle + progress * particle.drift;
      const x = centerX + Math.cos(angle) * radius;
      const y = centerY + Math.sin(angle) * radius * .68;
      const tail = Math.min(44, 3 + progress * 42) * particle.speed;
      context.beginPath();
      context.moveTo(x, y);
      context.lineTo(x + Math.cos(angle) * tail, y + Math.sin(angle) * tail * .68);
      context.strokeStyle = `rgba(${index % 5 ? "70,225,255" : "83,113,255"},${.18 + progress * .52})`;
      context.lineWidth = particle.size;
      context.stroke();
    });
    if (progress > .67) {
      const burst = (progress - .67) / .33;
      context.beginPath();
      context.arc(centerX, centerY, burst * Math.max(innerWidth, innerHeight) * .56, 0, Math.PI * 2);
      context.strokeStyle = `rgba(90,235,255,${Math.max(0, .55 - burst * .55)})`;
      context.lineWidth = 1.2;
      context.stroke();
    }
    $("#bootClock").textContent = new Date().toISOString().slice(11, 23).replace(".", ":");
    if (progress < 1) bootFrame = requestAnimationFrame(draw);
  };
  bootFrame = requestAnimationFrame(draw);
}

function dismissStartup(remember = true) {
  clearStartupTimers();
  stopBootAudio();
  const screen = $("#startupScreen");
  if (!screen) return;
  screen.classList.add("is-hidden");
  screen.classList.remove("is-running", "is-awaiting", "is-impact", "is-exiting");
  document.body.classList.remove("booting");
  if (remember) sessionStorage.setItem("feiwuStartupSeen", "1");
  const shell = $(".app-shell");
  shell.classList.remove("boot-reveal");
  requestAnimationFrame(() => shell.classList.add("boot-reveal"));
  setTimeout(() => shell.classList.remove("boot-reveal"), 760);
}

function runStartup(force = false) {
  const screen = $("#startupScreen");
  const stage = $("#startupStage");
  const detail = $("#startupDetail");
  const progress = $("#startupProgress");
  if (!screen || window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
    dismissStartup();
    return;
  }
  if (!force && sessionStorage.getItem("feiwuStartupSeen")) {
    screen.classList.add("is-hidden");
    return;
  }
  if (!force) {
    screen.classList.remove("is-hidden");
    screen.classList.add("is-awaiting");
    document.body.classList.add("booting");
    syncSoundControls();
    return;
  }

  clearStartupTimers();
  stopBootAudio();
  screen.className = "startup is-running phase-0";
  document.body.classList.add("booting");
  progress.style.transform = "scaleX(0)";
  document.querySelectorAll("#progressSegments i").forEach((segment) => segment.classList.remove("active"));
  startBootParticles();
  playBootAudio().catch(() => { /* browser denied audio; visuals continue */ });
  const sequence = [
    ["深海灵核唤醒", "CORE IGNITION · ENERGY CONTAINMENT", 0, "CORE BUS ........ CHARGING", "VECTOR ........... 041.77"],
    ["能量约束稳定", "REACTOR FIELD · 100% STABLE", 720, "ENERGY FIELD .... STABLE", "PRESSURE ......... NOMINAL"],
    ["安全边界扫描", "LOCAL ENCLAVE · IDENTITY VERIFIED", 1560, "SECURE ENCLAVE ... VERIFIED", "BIOMETRIC HASH ... MATCHED"],
    ["本地中转接入", "ANDROID CLIENT → FEIWU RELAY", 2460, "CLIENT CHANNEL ... ACQUIRED", "RELAY LATENCY ..... 001.7MS"],
    ["模型链路贯通", "FEIWU RELAY → MODEL PROVIDER", 3380, "UPLINK BUS ....... CONNECTED", "PACKET CIPHER .... ACTIVE"],
    ["灵核同步完成", "ALL SYSTEMS SYNCHRONIZED", 4360, "CORE BUS ........ ONLINE", "NEURAL VECTOR .... LOCKED"],
    ["费雾系统上线", "FEIWU DEEPSEA CORE · READY", 5260, "SYSTEM STATUS .... READY", "WELCOME BACK ...... OPERATOR"],
  ];
  sequence.forEach(([label, code, delay, leftLog, rightLog], index) => {
    startupTimers.push(setTimeout(() => {
      for (let phase = 0; phase < sequence.length; phase += 1) screen.classList.remove(`phase-${phase}`);
      screen.classList.add(`phase-${index}`);
      stage.style.opacity = "0";
      stage.style.transform = "translateY(7px) scale(.98)";
      setTimeout(() => {
        stage.textContent = label;
        detail.textContent = code;
        $("#bootLogLeft").textContent = leftLog;
        $("#bootLogRight").textContent = rightLog;
        stage.style.opacity = "1";
        stage.style.transform = "translateY(0) scale(1)";
      }, 65);
      progress.style.transform = `scaleX(${(index + 1) / sequence.length})`;
      document.querySelectorAll("#progressSegments i").forEach((segment, segmentIndex) => {
        segment.classList.toggle("active", segmentIndex <= index);
      });
      if (index === 5) {
        screen.classList.add("is-impact");
        setTimeout(() => screen.classList.remove("is-impact"), 460);
      }
    }, delay));
  });
  startupTimers.push(setTimeout(() => screen.classList.add("is-exiting"), 6060));
  startupTimers.push(setTimeout(() => dismissStartup(true), 6510));
}

async function api(path, options = {}) {
  const headers = new Headers(options.headers || {});
  headers.set("X-Feiwu-Admin-Token", adminToken);
  if (options.body) headers.set("Content-Type", "application/json");
  const response = await fetch(path, { ...options, headers });
  if (response.status === 401) {
    sessionStorage.removeItem("feiwuAdminToken");
    loginLayer.hidden = false;
    throw new Error("管理令牌不正确");
  }
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.detail || `请求失败（${response.status}）`);
  return data;
}

async function loadHealth() {
  const status = $("#serviceStatus");
  const startedAt = performance.now();
  try {
    const data = await fetch("/health", { cache: "no-store" }).then((response) => {
      if (!response.ok) throw new Error("健康检查失败");
      return response.json();
    });
    const latency = Math.max(1, Math.round(performance.now() - startedAt));
    status.classList.add("online");
    status.querySelector("span:last-child").textContent = data.provider_configured
      ? `${data.provider} 已就绪`
      : "服务在线 · 待配置密钥";
    $("#healthLatency").innerHTML = `${latency} <em>ms</em>`;
    $("#relayState").textContent = "在线";
    $("#providerState").textContent = data.provider_configured ? "已接入" : "待配置";
  } catch {
    status.classList.remove("online");
    status.querySelector("span:last-child").textContent = "服务连接异常";
    $("#healthLatency").innerHTML = `-- <em>ms</em>`;
    $("#relayState").textContent = "异常";
    $("#providerState").textContent = "不可读取";
  }
}

function renderConfig(data) {
  $("#baseUrl").value = data.base_url;
  $("#chatPath").value = data.chat_path;
  $("#modelName").value = data.model;
  $("#apiKey").value = "";
  $("#clearKey").checked = false;
  const badge = $("#keyBadge");
  badge.textContent = data.api_key_configured ? "API Key 已加密" : "尚未配置 API Key";
  badge.classList.toggle("ready", data.api_key_configured);
  const isDeepSeek = data.base_url.replace(/\/$/, "") === "https://api.deepseek.com";
  $("#providerPreset").value = isDeepSeek ? "deepseek" : "custom";
  $("#providerSummary").textContent = data.model || "等待配置";
  $("#providerState").textContent = data.api_key_configured ? "已接入" : "待配置";
}

async function loadConfig() {
  if (!adminToken) {
    loginLayer.hidden = false;
    return;
  }
  try {
    renderConfig(await api("/admin/api/config"));
    loginLayer.hidden = true;
  } catch (error) {
    $("#loginMessage").textContent = error.message;
  }
}

loginForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  adminToken = $("#adminToken").value.trim();
  sessionStorage.setItem("feiwuAdminToken", adminToken);
  $("#loginMessage").textContent = "";
  await loadConfig();
});

$("#providerPreset").addEventListener("change", (event) => {
  if (event.target.value === "deepseek") {
    $("#baseUrl").value = "https://api.deepseek.com";
    $("#chatPath").value = "/chat/completions";
    if (!$("#modelName").value || $("#modelName").value.startsWith("deepseek-")) {
      $("#modelName").value = "deepseek-flash";
    }
  }
});

$("#toggleKey").addEventListener("click", () => {
  const input = $("#apiKey");
  input.type = input.type === "password" ? "text" : "password";
  $("#toggleKey").textContent = input.type === "password" ? "显示" : "隐藏";
});

configForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const submit = configForm.querySelector("button[type=submit]");
  submit.disabled = true;
  submit.textContent = "正在安全保存…";
  try {
    const payload = {
      base_url: $("#baseUrl").value.trim(),
      chat_path: $("#chatPath").value.trim(),
      model: $("#modelName").value.trim(),
      api_key: $("#apiKey").value.trim() || null,
      clear_api_key: $("#clearKey").checked,
    };
    renderConfig(await api("/admin/api/config", { method: "PUT", body: JSON.stringify(payload) }));
    notify("配置已加密保存，并立即应用");
    await loadHealth();
  } catch (error) {
    notify(error.message);
  } finally {
    submit.disabled = false;
    submit.textContent = "保存并应用";
  }
});

async function testConnection(trigger) {
  const buttons = [$("#testButton"), $("#testButtonBottom")];
  buttons.forEach((button) => { button.disabled = true; });
  trigger.textContent = "正在测试链路…";
  $("#linkDiagnostic").textContent = "模型请求进行中";
  try {
    const data = await api("/admin/api/test", { method: "POST" });
    $("#linkDiagnostic").textContent = "链路正常";
    notify(`连接成功：${data.reply}`);
  } catch (error) {
    $("#linkDiagnostic").textContent = "链路异常";
    notify(error.message);
  } finally {
    buttons.forEach((button) => { button.disabled = false; });
    $("#testButton").textContent = "测试模型连接";
    $("#testButtonBottom").textContent = "运行连接测试";
  }
}

$("#testButton").addEventListener("click", (event) => testConnection(event.currentTarget));
$("#testButtonBottom").addEventListener("click", (event) => testConnection(event.currentTarget));

$("#copyEndpoint").addEventListener("click", async () => {
  try {
    await navigator.clipboard.writeText($("#phoneEndpoint").textContent);
    notify("手机服务地址已复制");
  } catch {
    notify("浏览器未允许剪贴板访问，请手动复制地址");
  }
});

function toggleBootSound() {
  soundEnabled = !soundEnabled;
  localStorage.setItem("feiwuBootSound", soundEnabled ? "on" : "off");
  syncSoundControls();
  if (!soundEnabled) stopBootAudio();
}

$("#igniteStartup").addEventListener("click", () => runStartup(true));
$("#skipStartup").addEventListener("click", () => dismissStartup(true));
$("#startupSound").addEventListener("click", toggleBootSound);
$("#toggleBootSound").addEventListener("click", toggleBootSound);
$("#replayStartup").addEventListener("click", () => runStartup(true));

document.querySelectorAll(".nav-item").forEach((link) => {
  link.addEventListener("click", () => {
    document.querySelectorAll(".nav-item").forEach((item) => {
      item.classList.remove("active");
      item.removeAttribute("aria-current");
    });
    link.classList.add("active");
    link.setAttribute("aria-current", "page");
  });
});

const visibleHost = ["localhost", "127.0.0.1"].includes(location.hostname)
  ? "电脑局域网IP"
  : location.hostname;
$("#phoneEndpoint").textContent = `${location.protocol}//${visibleHost}:${location.port || "8765"}`;

syncSoundControls();
runStartup(false);
loadHealth();
loadConfig();
