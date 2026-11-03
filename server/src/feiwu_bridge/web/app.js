const $ = (selector) => document.querySelector(selector);
const loginLayer = $("#loginLayer");
const loginForm = $("#loginForm");
const configForm = $("#configForm");
const toast = $("#toast");
let adminToken = sessionStorage.getItem("feiwuAdminToken") || "";
let toastTimer;

function notify(message) {
  toast.textContent = message;
  toast.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.remove("show"), 3200);
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
  try {
    const data = await fetch("/health").then((response) => response.json());
    status.classList.add("online");
    status.querySelector("span:last-child").textContent = data.provider_configured
      ? `${data.provider} 已就绪`
      : "服务在线 · 待配置密钥";
  } catch {
    status.classList.remove("online");
    status.querySelector("span:last-child").textContent = "服务连接异常";
  }
}

function renderConfig(data) {
  $("#baseUrl").value = data.base_url;
  $("#chatPath").value = data.chat_path;
  $("#modelName").value = data.model;
  $("#apiKey").value = "";
  $("#clearKey").checked = false;
  const badge = $("#keyBadge");
  badge.textContent = data.api_key_configured ? "API Key 已加密保存" : "尚未配置 API Key";
  badge.classList.toggle("ready", data.api_key_configured);
  const isDeepSeek = data.base_url.replace(/\/$/, "") === "https://api.deepseek.com";
  $("#providerPreset").value = isDeepSeek ? "deepseek" : "custom";
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
  }
});

$("#testButton").addEventListener("click", async (event) => {
  event.currentTarget.disabled = true;
  event.currentTarget.textContent = "正在请求模型…";
  try {
    const data = await api("/admin/api/test", { method: "POST" });
    notify(`连接成功：${data.reply}`);
  } catch (error) {
    notify(error.message);
  } finally {
    event.currentTarget.disabled = false;
    event.currentTarget.textContent = "测试模型连接";
  }
});

$("#copyEndpoint").addEventListener("click", async () => {
  const value = $("#phoneEndpoint").textContent;
  await navigator.clipboard.writeText(value);
  notify("手机服务地址已复制");
});

const visibleHost = ["localhost", "127.0.0.1"].includes(location.hostname)
  ? "电脑局域网IP"
  : location.hostname;
$("#phoneEndpoint").textContent = `${location.protocol}//${visibleHost}:${location.port || "8765"}`;

loadHealth();
loadConfig();
