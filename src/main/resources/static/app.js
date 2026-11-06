// the booking page: service -> day -> time -> details. Plain JS, no build step.

const $ = (id) => document.getElementById(id);
const WEEKDAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
const DAYS_SHOWN = 14;

// pro is the barber picked, or null for whoever is free
const state = { business: null, services: [], service: null, pro: null, days: [], date: null, time: null };

// "R$ 45", and the cents only when there are any
const price = (cents) => new Intl.NumberFormat('pt-BR', {
  style: 'currency', currency: 'BRL', minimumFractionDigits: cents % 100 ? 2 : 0,
}).format(cents / 100);
const hhmm = (t) => t.slice(0, 5);

// dates come as "2026-11-03"; reading them as UTC keeps the browser's timezone out of it
function parseDate(iso) {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d));
}
function formatDate(iso, opts) {
  return parseDate(iso).toLocaleDateString('en-US', { timeZone: 'UTC', ...opts });
}
function weekdayOf(iso) {
  return WEEKDAYS[(parseDate(iso).getUTCDay() + 6) % 7];
}
function addMinutes(time, minutes) {
  const [h, m] = time.split(':').map(Number);
  const total = h * 60 + m + minutes;
  return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`;
}
function duration(min) {
  if (min < 60) return `${min} min`;
  return min % 60 ? `${Math.floor(min / 60)} h ${min % 60}` : `${min / 60} h`;
}

async function api(path, options) {
  const res = await fetch(path, options);
  const body = res.status === 204 ? null : await res.json().catch(() => null);
  if (!res.ok) throw Object.assign(new Error(body?.error || 'Something went wrong'), { status: res.status, body });
  return body;
}

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === 'class') node.className = v;
    else if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
    else if (v !== false && v != null) node.setAttribute(k, v === true ? '' : v);
  }
  node.append(...children.filter((c) => c != null));
  return node;
}

// header

function renderBusiness() {
  const b = state.business;
  document.title = `Book a time · ${b.name}`;
  $('business-name').textContent = b.name;
  $('business-address').textContent = b.address;

  const today = b.now.slice(0, 10);
  const ranges = b.hours[weekdayOf(today)] || [];
  $('today-hours').replaceChildren(ranges.length
    ? el('span', {}, 'Open today ', el('b', {}, ranges.map((r) => `${hhmm(r.start)}–${hhmm(r.end)}`).join(', ')))
    : el('span', { class: 'closed' }, 'Closed today'));

  const notice = b.minNoticeMinutes >= 60 ? `${b.minNoticeMinutes / 60} hours` : `${b.minNoticeMinutes} minutes`;
  $('zone-note').textContent = `Times are in ${b.zone.split('/').pop().replace('_', ' ')} time. Book at least ${notice} ahead.`;
}

// 1. service

function renderServices() {
  const list = $('services');
  if (!state.services.length) {
    list.replaceChildren(el('li', { class: 'loading' }, 'No services available right now.'));
    return;
  }
  list.replaceChildren(...state.services.map((s) => el('li', {},
    el('button', {
      type: 'button', class: 'service', role: 'radio',
      'aria-checked': String(state.service?.id === s.id),
      onclick: () => pickService(s),
    },
    el('span', { class: 'name' }, s.name),
    s.description ? el('span', { class: 'desc' }, s.description) : null,
    el('span', { class: 'price' }, price(s.priceCents), el('span', {}, duration(s.durationMin)))),
  )));
}

async function pickService(service) {
  state.service = service;
  state.time = null;
  renderServices();
  renderWho();
  $('step-day').hidden = false;
  $('step-details').hidden = true;
  await loadDays();
}

// 2. who and day

const withPro = () => (state.pro ? `&professionalId=${state.pro.id}` : '');

// only when there's more than one person to pick from
function renderWho() {
  const pros = state.business.professionals;
  $('who').hidden = pros.length < 2;
  if (pros.length < 2) return;
  const choice = (pro, label) => el('button', {
    type: 'button', role: 'radio', class: 'who-pick',
    'aria-checked': String((state.pro?.id ?? null) === (pro?.id ?? null)),
    onclick: () => pickPro(pro),
  }, label);
  $('who').replaceChildren(el('span', { class: 'who-label' }, 'With'),
    choice(null, 'whoever is free'), ...pros.map((p) => choice(p, p.name)));
}

async function pickPro(pro) {
  state.pro = pro;
  state.time = null;
  renderWho();
  $('step-details').hidden = true;
  await loadDays();
}

async function loadDays() {
  const today = state.business.now.slice(0, 10);
  state.days = await api(`/api/availability/days?serviceId=${state.service.id}&from=${today}&count=${DAYS_SHOWN}${withPro()}`);
  // keep the chosen day if it still has room, otherwise jump to the first one that does
  const current = state.days.find((d) => d.date === state.date && d.free > 0);
  const first = state.days.find((d) => d.free > 0);
  renderDays();
  if (current || first) await pickDay((current || first).date, { scroll: false });
  else {
    $('step-time').hidden = false;
    $('time-day').textContent = '';
    $('times').replaceChildren(el('p', { class: 'empty' }, `Nothing free in the next ${DAYS_SHOWN} days.`));
  }
}

function renderDays() {
  const today = state.business.now.slice(0, 10);
  $('days').replaceChildren(...state.days.map((d) => {
    const label = !d.open ? 'closed' : d.free === 0 ? 'full' : `${d.free} free`;
    return el('li', {}, el('button', {
      type: 'button', class: 'day',
      disabled: d.free === 0,
      'aria-pressed': String(d.date === state.date),
      'aria-label': `${formatDate(d.date, { weekday: 'long', month: 'long', day: 'numeric' })}, ${label}`,
      onclick: () => pickDay(d.date),
    },
    el('span', { class: 'wd' }, d.date === today ? 'Today' : formatDate(d.date, { weekday: 'short' })),
    el('span', { class: 'dn' }, String(parseDate(d.date).getUTCDate())),
    el('span', { class: 'free' }, label)));
  }));
}

async function pickDay(date, { scroll = true } = {}) {
  state.date = date;
  state.time = null;
  $('time-notice').hidden = true;
  renderDays();
  $('step-details').hidden = true;
  $('step-time').hidden = false;
  $('time-day').textContent = formatDate(date, { weekday: 'long', month: 'short', day: 'numeric' });
  const { slots } = await api(`/api/availability?serviceId=${state.service.id}&date=${date}${withPro()}`);
  renderTimes(slots.map(hhmm));
  if (scroll) $('step-time').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

// 3. time

function renderTimes(slots) {
  if (!slots.length) {
    $('times').replaceChildren(el('p', { class: 'empty' }, 'No times left on this day. ',
      el('button', { type: 'button', onclick: loadDays }, 'See other days')));
    return;
  }
  const periods = [
    ['Morning', (t) => t < '12:00'],
    ['Afternoon', (t) => t >= '12:00' && t < '18:00'],
    ['Evening', (t) => t >= '18:00'],
  ];
  $('times').replaceChildren(...periods
    .map(([name, test]) => [name, slots.filter(test)])
    .filter(([, list]) => list.length)
    .map(([name, list]) => el('div', { class: 'period' },
      el('h3', {}, name),
      el('div', { class: 'slots' }, ...list.map((t) => el('button', {
        type: 'button', class: 'slot', 'aria-pressed': String(t === state.time), onclick: () => pickTime(t),
      }, t))))));
}

function pickTime(time) {
  state.time = time;
  $('time-notice').hidden = true;
  for (const b of document.querySelectorAll('.slot')) b.setAttribute('aria-pressed', String(b.textContent === time));
  const s = state.service;
  $('summary').replaceChildren(
    el('b', {}, s.name), ', ',
    formatDate(state.date, { weekday: 'long', month: 'long', day: 'numeric' }),
    ` from ${time} to ${addMinutes(time, s.durationMin)}${state.pro ? ` with ${state.pro.name}` : ''} · ${price(s.priceCents)}`);
  $('form-error').hidden = true;
  $('step-details').hidden = false;
  $('step-details').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

// 4. details

$('details').addEventListener('submit', async (event) => {
  event.preventDefault();
  const form = event.target;
  const name = form.clientName.value.trim();
  const phone = form.clientPhone.value.trim();
  const problems = [];
  form.clientName.setAttribute('aria-invalid', String(!name));
  if (!name) problems.push('your name');
  const phoneOk = /^[0-9 ()+-]{8,20}$/.test(phone) && phone.replace(/\D/g, '').length >= 8;
  form.clientPhone.setAttribute('aria-invalid', String(!phoneOk));
  if (!phoneOk) problems.push('a phone number we can call');
  if (problems.length) return showError(`Please add ${problems.join(' and ')}.`);

  const button = form.querySelector('.confirm');
  button.disabled = true;
  button.textContent = 'Booking…';
  try {
    const booking = await api('/api/bookings', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        serviceId: state.service.id, start: `${state.date}T${state.time}`, clientName: name, clientPhone: phone,
        professionalId: state.pro?.id ?? null,
      }),
    });
    showDone(booking);
  } catch (err) {
    if (err.status === 409) {
      // someone got there first, or the time passed while the page was open:
      // back to the times, already refreshed, with what happened on top
      const lost = state.time;
      const why = err.body?.problems?.map((p) => p.message).join(' ') || err.message;
      await loadDays();
      const notice = $('time-notice');
      notice.textContent = `${lost} is gone. ${why} Pick another time, your details are still there.`;
      notice.hidden = false;
      $('step-time').scrollIntoView({ behavior: 'smooth', block: 'start' });
    } else {
      showError(err.body?.problems?.map((p) => `${p.code}: ${p.message}`).join('. ') || err.message);
    }
  } finally {
    button.disabled = false;
    button.textContent = 'Confirm booking';
  }
});

function showError(message) {
  const box = $('form-error');
  box.textContent = message;
  box.hidden = false;
}

function showDone(booking) {
  const start = booking.starts.slice(11, 16);
  const end = booking.ends.slice(11, 16);
  const date = booking.starts.slice(0, 10);
  $('done-title').textContent = `See you ${formatDate(date, { weekday: 'long' })}, ${booking.clientName.split(' ')[0]}.`;
  const rows = [
    ['Service', booking.service],
    ...(state.business.professionals.length > 1 ? [['With', booking.professional]] : []),
    ['When', `${formatDate(date, { weekday: 'long', month: 'long', day: 'numeric' })}, ${start}–${end}`],
    ['Where', state.business.address],
    ['Booking', `#${booking.id}`],
  ];
  $('receipt').replaceChildren(...rows.flatMap(([k, v]) => [el('dt', {}, k), el('dd', {}, v)]));

  // the token goes after the #, which the browser never sends to a server (not even in the
  // Referer of another site), so it doesn't end up in anyone's logs
  const link = new URL(`cancel.html#${booking.cancelToken}`, location.href).href;
  $('cancel-link').href = link;
  $('cancel-link').textContent = link.replace(/^https?:\/\//, '');
  const until = booking.cancelUntil;
  $('cancel-until').textContent = `${formatDate(until.slice(0, 10), { weekday: 'short', month: 'short', day: 'numeric' })} at ${until.slice(11, 16)}`;
  $('cancel-box').hidden = !booking.canCancel;
  $('booking').hidden = true;
  $('done').hidden = false;
  $('done').focus();
  window.scrollTo({ top: 0 });
}

$('copy-link').addEventListener('click', async () => {
  try {
    await navigator.clipboard.writeText($('cancel-link').href);
    $('copy-link').textContent = 'Copied';
  } catch {
    $('copy-link').textContent = 'Select it and copy';
  }
  setTimeout(() => { $('copy-link').textContent = 'Copy'; }, 2000);
});

$('again').addEventListener('click', async () => {
  $('done').hidden = true;
  $('booking').hidden = false;
  $('details').reset();
  state.time = null;
  $('step-details').hidden = true;
  await loadDays();
});

async function start() {
  try {
    [state.business, state.services] = await Promise.all([api('/api/business'), api('/api/services')]);
    renderBusiness();
    renderServices();
  } catch {
    $('services').replaceChildren(el('li', { class: 'loading' }, "Couldn't load the services. Try again in a moment."));
  }
}

start();
