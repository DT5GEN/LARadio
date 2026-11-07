const stations = [
  {
    id: "real923",
    name: "REAL 92.3",
    frequency: "KRRL 92.3 FM",
    description: "Hip-Hop",
    streams: [],
    official: "https://www.iheart.com/live/real-923-181/"
  },
  {
    id: "power106",
    name: "POWER 106",
    frequency: "KPWR 105.9 FM",
    description: "Hip-Hop",
    streams: [
      "https://playerservices.streamtheworld.com/api/livestream-redirect/KPWRFM.mp3",
      "https://playerservices.streamtheworld.com/api/livestream-redirect/KPWRFMAAC.aac"
    ],
    official: "https://player.listenlive.co/44031/"
  },
  {
    id: "kiis",
    name: "102.7 KIIS-FM",
    frequency: "102.7 FM",
    description: "Top 40 / R&B & Hip-Hop",
    streams: [],
    official: "https://www.iheart.com/live/1027-kiis-fm-los-angeles-185/"
  },
  {
    id: "kday",
    name: "93.5 KDAY",
    frequency: "93.5 FM",
    description: "Classic Hip-Hop",
    streams: [
      "https://playerservices.streamtheworld.com/api/livestream-redirect/KDAYFM.mp3"
    ],
    official: "https://player.listenlive.co/35791"
  },
  {
    id: "cali939",
    name: "CALI 93.9",
    frequency: "KLLI 93.9 FM",
    description: "Latin Urban / Reggaeton",
    streams: [
      "https://playerservices.streamtheworld.com/api/livestream-redirect/KLLI_FMAAC.aac",
      "https://playerservices.streamtheworld.com/api/livestream-redirect/KLLI_FMAAC_SC"
    ],
    official: "https://www.cali939.com/listen-live/"
  },
  {
    id: "kjlh",
    name: "102.3 KJLH",
    frequency: "102.3 FM",
    description: "Urban AC / R&B (+ Hip-Hop)",
    streams: [
      "http://stream.abacast.net/direct/kjlh-kjlhfmaac-ibc2"
    ],
    official: "https://www.kjlhradio.com/listenlive/"
  }
];

const els = {
  stations: document.querySelector("#stations"),
  log: document.querySelector("#logWindow"),
  eq: document.querySelector("#equalizer"),
  status: document.querySelector("#statusPill"),
  install: document.querySelector("#installBtn"),
  banner: document.querySelector("#notificationBanner"),
  allow: document.querySelector("#allowNotifications"),
  deny: document.querySelector("#denyNotifications")
};

let activeId = null;
let activeAudio = null;
let activeStation = null;
let audioContext = null;
let analyser = null;
let sourceNode = null;
let rafId = null;
let installPrompt = null;
let streamTimer = null;
let streamAttempt = 0;

const EQ_COLUMNS = 32;
const EQ_ROWS = 8;
const eqColumns = [];

function timestamp() {
  return new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

function log(message) {
  const line = document.createElement("div");
  line.className = "log-line";
  line.textContent = `[${timestamp()}] ${message}`;
  els.log.appendChild(line);
  while (els.log.children.length > 80) els.log.removeChild(els.log.firstChild);
  els.log.scrollTop = els.log.scrollHeight;
}

function setStatus(text, playing = false) {
  els.status.textContent = text;
  els.status.classList.toggle("playing", playing);
}

function buildEqualizer() {
  els.eq.innerHTML = "";
  for (let c = 0; c < EQ_COLUMNS; c++) {
    const column = document.createElement("div");
    column.className = "eq-column";
    const dots = [];
    for (let r = 0; r < EQ_ROWS; r++) {
      const dot = document.createElement("span");
      dot.className = "eq-dot";
      column.appendChild(dot);
      dots.push(dot);
    }
    els.eq.appendChild(column);
    eqColumns.push(dots);
  }
}

function renderStations() {
  els.stations.innerHTML = "";
  for (const station of stations) {
    const card = document.createElement("article");
    card.className = "card";
    card.dataset.id = station.id;

    const audio = document.createElement("audio");
    audio.controls = true;
    audio.preload = "none";
    audio.setAttribute("aria-label", `${station.name} audio player`);

    const head = document.createElement("div");
    head.className = "card-head";
    head.innerHTML = `
      <div>
        <h3>${station.name}</h3>
        <p>${station.description}</p>
      </div>
      <span class="frequency">${station.frequency}</span>
    `;

    const actions = document.createElement("div");
    actions.className = "actions";

    const listen = makeButton("Listen ▶", "btn primary", () => startStation(station));
    const stop = makeButton("Stop ⏸", "btn", () => stopStation(station));
    actions.append(listen, stop);

    if (station.official) {
      actions.append(makeButton("Official player", "btn", () => openOfficial(station)));
    }

    audio.addEventListener("play", () => {
      if (activeAudio !== audio) {
        stopActive(false);
        activeAudio = audio;
        activeStation = station;
        activeId = station.id;
        markActive();
      }
      setStatus(`PLAYING ${station.name}`, true);
      log(`[PLAY] ${station.name}`);
      setupAnalyser(audio);
      notifyNowPlaying(station);
      startEqualizer();
    });

    audio.addEventListener("pause", () => {
      if (activeAudio === audio) {
        stopEqualizer();
        setStatus("PAUSED");
      }
    });

    audio.addEventListener("error", () => {
      if (activeAudio === audio || station.id === activeId) {
        log(`[ERROR] Stream failed due to CORS/DRM or unavailable source`);
      }
    });

    card.append(head, audio, actions);
    els.stations.appendChild(card);
  }
}

function makeButton(label, className, handler) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = className;
  button.textContent = label;
  button.addEventListener("click", handler);
  return button;
}

function markActive() {
  document.querySelectorAll(".card").forEach(card => {
    card.classList.toggle("active", card.dataset.id === activeId);
  });
}

function getAudio(station) {
  return document.querySelector(`.card[data-id="${station.id}"] audio`);
}

async function startStation(station) {
  clearTimeout(streamTimer);
  stopActive(false);

  if (!station.streams.length) {
    log(`[FALLBACK] ${station.name} has no inline stream; opening official player`);
    openOfficial(station);
    return;
  }

  const audio = getAudio(station);
  activeAudio = audio;
  activeStation = station;
  activeId = station.id;
  markActive();
  setStatus(`CONNECTING ${station.name}`);

  streamAttempt = 0;
  await tryNextStream(station, audio);
}

async function tryNextStream(station, audio) {
  if (streamAttempt >= station.streams.length) {
    log(`[ERROR] All inline streams failed for ${station.name}`);
    openOfficial(station);
    return;
  }

  const url = station.streams[streamAttempt++];
  audio.pause();
  audio.currentTime = 0;
  audio.src = url;
  audio.load();

  let settled = false;
  const cleanup = () => {
    audio.removeEventListener("playing", onPlaying);
    audio.removeEventListener("error", onError);
    clearTimeout(streamTimer);
  };

  const onPlaying = () => {
    if (settled) return;
    settled = true;
    cleanup();
  };

  const onError = async () => {
    if (settled) return;
    settled = true;
    cleanup();
    log(`[ERROR] Stream attempt failed for ${station.name}`);
    await tryNextStream(station, audio);
  };

  audio.addEventListener("playing", onPlaying, { once: true });
  audio.addEventListener("error", onError, { once: true });

  streamTimer = setTimeout(async () => {
    if (settled) return;
    settled = true;
    cleanup();
    log(`[ERROR] Stream timed out after ~1.5s for ${station.name}`);
    await tryNextStream(station, audio);
  }, 1500);

  try {
    await audio.play();
  } catch (error) {
    if (!settled) {
      settled = true;
      cleanup();
      log(`[ERROR] Playback rejected: ${error?.name || "unknown error"}`);
      await tryNextStream(station, audio);
    }
  }
}

function stopStation(station) {
  const audio = getAudio(station);
  if (audio) {
    audio.pause();
    audio.currentTime = 0;
    if (activeAudio === audio) stopActive(false);
  }
  log(`[STOP] ${station.name}`);
  setStatus("IDLE");
}

function stopActive(writeLog = true) {
  clearTimeout(streamTimer);
  stopEqualizer();

  if (activeAudio) {
    const previous = activeStation;
    activeAudio.pause();
    try { activeAudio.currentTime = 0; } catch {}
    if (writeLog && previous) log(`[STOP] ${previous.name}`);
  }

  activeAudio = null;
  activeStation = null;
  activeId = null;
  markActive();
  setStatus("IDLE");
}

function openOfficial(station) {
  if (!station.official) return;
  log(`[FALLBACK] Opening official player`);
  window.open(station.official, "_blank", "noopener,noreferrer");
}

function setupAnalyser(audio) {
  if (!window.AudioContext && !window.webkitAudioContext) return;

  try {
    if (!audioContext) {
      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      audioContext = new AudioCtx();
    }

    if (sourceNode && sourceNode.mediaElement === audio) {
      if (audioContext.state === "suspended") audioContext.resume();
      return;
    }

    // A MediaElementSource can only be created once for a given media element.
    if (!audio._radioSource) {
      audio._radioSource = audioContext.createMediaElementSource(audio);
    }
    sourceNode = audio._radioSource;
    analyser = audioContext.createAnalyser();
    analyser.fftSize = 256;
    analyser.smoothingTimeConstant = 0.82;
    sourceNode.connect(analyser);
    analyser.connect(audioContext.destination);

    if (audioContext.state === "suspended") audioContext.resume();
  } catch (error) {
    // Cross-origin streams often do not provide CORS headers. The audio element
    // may still play, but Web Audio analysis cannot safely inspect the stream.
    analyser = null;
    sourceNode = null;
    log(`[ERROR] Equalizer analysis unavailable for this stream`);
  }
}

function startEqualizer() {
  cancelAnimationFrame(rafId);

  const fallback = !analyser;
  const data = analyser ? new Uint8Array(analyser.frequencyBinCount) : null;

  const frame = () => {
    if (!activeAudio || activeAudio.paused) {
      drawEqualizer(null);
      rafId = null;
      return;
    }

    if (analyser) analyser.getByteFrequencyData(data);
    drawEqualizer(data, fallback);
    rafId = requestAnimationFrame(frame);
  };

  frame();
}

function stopEqualizer() {
  cancelAnimationFrame(rafId);
  rafId = null;
  drawEqualizer(null);
}

function drawEqualizer(data, fallback = false) {
  const values = [];
  for (let i = 0; i < EQ_COLUMNS; i++) {
    if (!data) {
      values.push(fallback ? 2 + Math.floor(Math.random() * 2) : 0);
      continue;
    }
    const start = Math.floor((i / EQ_COLUMNS) * data.length * .72);
    const end = Math.max(start + 1, Math.floor(((i + 1) / EQ_COLUMNS) * data.length * .72));
    let sum = 0;
    for (let j = start; j < end; j++) sum += data[j];
    const avg = sum / (end - start);
    values.push(Math.max(0, Math.min(EQ_ROWS, Math.round((avg / 255) * EQ_ROWS * 1.25))));
  }

  eqColumns.forEach((dots, i) => {
    const height = values[i] ?? 0;
    dots.forEach((dot, row) => {
      dot.classList.toggle("lit", row >= EQ_ROWS - height);
    });
  });
}

async function notifyNowPlaying(station) {
  if (!("Notification" in window) || Notification.permission !== "granted") return;
  try {
    new Notification("Now playing", {
      body: `${station.name} • ${station.description}`,
      icon: "./icons/icon-192.png",
      tag: "radio-now-playing"
    });
  } catch {}
}

function initNotificationPrompt() {
  if (!("Notification" in window)) return;
  if (localStorage.getItem("radioNotificationChoice")) return;

  els.banner.hidden = false;

  els.allow.addEventListener("click", async () => {
    try {
      await Notification.requestPermission();
    } finally {
      localStorage.setItem("radioNotificationChoice", "allow");
      els.banner.hidden = true;
    }
  });

  els.deny.addEventListener("click", () => {
    localStorage.setItem("radioNotificationChoice", "deny");
    els.banner.hidden = true;
  });
}

function initInstallPrompt() {
  window.addEventListener("beforeinstallprompt", event => {
    event.preventDefault();
    installPrompt = event;
    els.install.hidden = false;
  });

  els.install.addEventListener("click", async () => {
    if (!installPrompt) return;
    const promptEvent = installPrompt;
    installPrompt = null;
    els.install.hidden = true;
    await promptEvent.prompt();
    await promptEvent.userChoice;
  });

  window.addEventListener("appinstalled", () => {
    els.install.hidden = true;
    installPrompt = null;
    log(`[INSTALL] App installed`);
  });
}

async function registerServiceWorker() {
  if (!("serviceWorker" in navigator)) return;
  try {
    await navigator.serviceWorker.register("./service-worker.js", { scope: "./" });
    log(`[SYSTEM] Service worker ready`);
  } catch (error) {
    log(`[ERROR] Service worker registration failed`);
  }
}

buildEqualizer();
renderStations();
log(`[SYSTEM] Radio console initialized`);
initNotificationPrompt();
initInstallPrompt();
registerServiceWorker();
