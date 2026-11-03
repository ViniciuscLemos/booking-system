// the business's side: log in and see the day's bookings, with the free gaps between them

const $ = (id) => document.getElementById(id);
const WEEKDAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
const state = { business: null, date: null, today: null };

const hhmm = (t) => t.slice(0, 5);
const minutes = (t) => Number(t.slice(0, 2)) * 60 + Number(t.slice(3, 5));
const clock = (m) => `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;

function parseDate(iso) {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d));
}
function shift(iso, days) {
  const d = parseDate(iso);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}
const longDate = (iso) => parseDate(iso).toLocaleDateString('en-US', { timeZone: 'UTC', weekday: 'long', month: 'long', day: 'numeric' });
const weekdayOf = (iso) => WEEKDAYS[(parseDate(iso).getUTCDay() + 6) % 7];

// the CSRF token comes in a cookie and has to go back in a header on every change
function xsrf() {
  const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : '';
}

async function api(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (options.method && options.method !== 'GET') headers['X-XSRF-TOKEN'] = xsrf();
  const res = await fetch(path, { ...options, headers, credentials: 'same-origin' });
  const body = res.status === 204 ? null : await res.json().catch(() => null);
  if (!res.ok) throw Object.assign(new Error(body?.error || 'Something went wrong'), { status: res.status });
  return body;
}

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === 'class') node.className = v;
    else if (v != null && v !== false) node.setAttribute(k, v);
  }
  node.append(...children.filter((c) => c != null));
  return node;
}

// "11987654321" -> "(11) 98765-4321"; anything else is shown as typed
function prettyPhone(text) {
  const d = text.replace(/\D/g, '');
  if (d.length === 11) return `(${d.slice(0, 2)}) ${d.slice(2, 7)}-${d.slice(7)}`;
  if (d.length === 10) return `(${d.slice(0, 2)}) ${d.slice(2, 6)}-${d.slice(6)}`;
  return text;
}

function show(view) {
  $('login-view').hidden = view !== 'login';
  $('agenda-view').hidden = view !== 'agenda';
  $('who').hidden = view !== 'agenda';
}

// login

$('login').addEventListener('submit', async (event) => {
  event.preventDefault();
  const form = event.target;
  const error = $('login-error');
  const button = form.querySelector('.confirm');
  error.hidden = true;
  if (!form.email.value.trim() || !form.password.value) {
    error.textContent = 'Type your e-mail and password.';
    error.hidden = false;
    return;
  }
  button.disabled = true;
  try {
    const me = await api('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: form.email.value.trim(), password: form.password.value }),
    });
    form.reset();
    await openAgenda(me);
  } catch (err) {
    error.textContent = err.message;
    error.hidden = false;
    form.password.select();
  } finally {
    button.disabled = false;
  }
});

$('logout').addEventListener('click', async () => {
  await api('/api/auth/logout', { method: 'POST' }).catch(() => {});
  show('login');
  document.querySelector('#login input[name=email]').focus();
});

// agenda

async function openAgenda(me) {
  $('who-name').textContent = me.name;
  show('agenda');
  await loadDay(state.date || state.today);
}

async function loadDay(date) {
  state.date = date;
  $('agenda-day').textContent = date === state.today ? `Today, ${longDate(date).split(', ')[1]}` : longDate(date);
  $('today').hidden = date === state.today;
  let bookings;
  try {
    bookings = await api(`/api/admin/bookings?date=${date}`);
  } catch (err) {
    if (err.status === 401) return show('login');
    $('agenda').replaceChildren(el('li', { class: 'empty' }, err.message));
    return;
  }
  render(bookings);
}

// bookings in order, with the free time between them inside the working hours
function rows(bookings, ranges) {
  const out = [];
  const sorted = [...bookings].sort((a, b) => a.starts.localeCompare(b.starts));
  let i = 0;
  ranges.forEach((range, r) => {
    let cursor = minutes(range.start);
    const end = minutes(range.end);
    for (; i < sorted.length && minutes(sorted[i].starts.slice(11)) < end; i++) {
      const b = sorted[i];
      const start = minutes(b.starts.slice(11));
      if (start - cursor >= 15) out.push({ free: true, from: cursor, to: start });
      out.push({ booking: b });
      cursor = Math.max(cursor, minutes(b.blockedUntil.slice(11)));
    }
    if (end - cursor >= 15) out.push({ free: true, from: cursor, to: end });
    // the gap until the next range of the day is the break
    if (r < ranges.length - 1) out.push({ gap: true, from: end, to: minutes(ranges[r + 1].start) });
  });
  // a booking outside the regular hours (special date) still shows up
  for (; i < sorted.length; i++) out.push({ booking: sorted[i] });
  return out;
}

function render(all) {
  const ranges = state.business.hours[weekdayOf(state.date)] || [];
  const list = $('agenda');
  const bookings = all.filter((b) => b.status === 'CONFIRMED');
  const cancelled = all.filter((b) => b.status === 'CANCELLED');

  if (!ranges.length && !all.length) {
    $('agenda-summary').textContent = '';
    list.replaceChildren(el('li', { class: 'empty' }, 'Closed on this day.'));
    return;
  }

  const booked = bookings.reduce((sum, b) => sum + minutes(b.ends.slice(11)) - minutes(b.starts.slice(11)), 0);
  const open = ranges.reduce((sum, r) => sum + minutes(r.end) - minutes(r.start), 0);
  $('agenda-summary').textContent = bookings.length
    ? `${bookings.length} booking${bookings.length === 1 ? '' : 's'} · ${open ? Math.round((100 * booked) / open) : 0}% of the day booked`
    : 'Nothing booked yet.';
  if (cancelled.length) $('agenda-summary').textContent += ` · ${cancelled.length} cancelled`;

  list.replaceChildren(...rows(bookings, ranges).map((row) => {
    if (row.gap) {
      return el('li', { class: 'break' }, el('span', { class: 'when' }), el('span', {}, `Break, ${clock(row.from)} to ${clock(row.to)}`));
    }
    if (row.free) {
      return el('li', { class: 'free-row' },
        el('span', { class: 'when' }, clock(row.from)),
        el('span', {}, `Free until ${clock(row.to)}`));
    }
    return bookingRow(row.booking);
  }));

  // cancelled ones go at the end, so they don't get in the way of the day but the staff
  // still sees why a slot opened up
  if (cancelled.length) {
    list.append(el('li', { class: 'section' }, el('span', {}, 'Cancelled')));
    for (const b of cancelled) list.append(bookingRow(b));
  }
}

function bookingRow(b) {
  const phone = b.clientPhone.replace(/[^\d+]/g, '');
  const isCancelled = b.status === 'CANCELLED';
  const who = el('span', { class: 'who-what' }, el('b', {}, b.clientName), el('span', {}, b.serviceName));
  if (isCancelled) {
    const by = b.cancelledBy === 'CLIENT' ? 'by the client' : 'by the staff';
    who.append(el('span', { class: 'cancelled-stamp' }, `Cancelled ${by} at ${hhmm(b.cancelledAt.slice(11))}`));
  }
  const side = el('span', { class: 'side' }, el('a', { class: 'phone', href: `tel:${phone}` }, prettyPhone(b.clientPhone)));
  if (!isCancelled) {
    const cancel = el('button', { type: 'button', class: 'link small' }, 'Cancel');
    cancel.addEventListener('click', async () => {
      // the first click asks, the second one does it
      if (!cancel.dataset.sure) {
        cancel.dataset.sure = '1';
        cancel.textContent = 'Sure? Cancel it';
        return;
      }
      cancel.disabled = true;
      try {
        await api(`/api/admin/bookings/${b.id}/cancel`, { method: 'POST' });
      } catch (err) {
        if (err.status === 401) return show('login');
      }
      await loadDay(state.date);
    });
    side.append(cancel);
  }
  return el('li', { class: isCancelled ? 'booking-row is-cancelled' : 'booking-row' },
    el('span', { class: 'when' }, hhmm(b.starts.slice(11)), el('small', {}, hhmm(b.ends.slice(11)))),
    who, side);
}

$('prev').addEventListener('click', () => loadDay(shift(state.date, -1)));
$('next').addEventListener('click', () => loadDay(shift(state.date, 1)));
$('today').addEventListener('click', () => loadDay(state.today));

async function start() {
  state.business = await api('/api/business');
  state.today = state.business.now.slice(0, 10);
  $('business-name').textContent = state.business.name;
  document.title = `Staff · ${state.business.name}`;
  try {
    await openAgenda(await api('/api/auth/me'));
  } catch {
    show('login');
  }
}

start();
