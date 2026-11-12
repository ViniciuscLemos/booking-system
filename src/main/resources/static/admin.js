// the business's side: log in and see the day's bookings, with the free gaps between them

const $ = (id) => document.getElementById(id);
const WEEKDAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
// pro: whose agenda is showing, null for everyone
const state = { business: null, date: null, today: null, pro: null };

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
  if (!res.ok) throw Object.assign(new Error(body?.error || 'Something went wrong'), { status: res.status, problems: body?.problems });
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

// one view at a time; the tabs and the name only once logged in
function show(view) {
  for (const name of ['login', 'agenda', 'month', 'services', 'hours', 'days']) $(`${name}-view`).hidden = view !== name;
  $('who').hidden = view === 'login';
  $('tabs').hidden = view === 'login';
  for (const tab of document.querySelectorAll('#tabs a')) {
    if (tab.dataset.view === view) tab.setAttribute('aria-current', 'page');
    else tab.removeAttribute('aria-current');
  }
}

$('tabs').addEventListener('click', (event) => {
  const tab = event.target.closest('a[data-view]');
  if (!tab) return;
  event.preventDefault();
  const view = tab.dataset.view;
  show(view);
  if (view === 'agenda') loadDay(state.date);
  if (view === 'month') loadMonth(state.month || state.today.slice(0, 7));
  if (view === 'services') loadServices();
  if (view === 'hours') loadHours();
  if (view === 'days') loadDaysOff();
});

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
  let day;
  try {
    // the day's own hours (a special date changes them) and the time away
    [bookings, day] = await Promise.all([api(`/api/admin/bookings?date=${date}`), api(`/api/admin/day?date=${date}`)]);
  } catch (err) {
    if (err.status === 401) return show('login');
    $('agenda').replaceChildren(el('li', { class: 'empty' }, err.message));
    return;
  }
  render(bookings, day);
  loadWaiting(date);
}

// who's on the waiting list for the day, first in line first, with a tap to call or message
async function loadWaiting(date) {
  let people = [];
  try { people = await api(`/api/admin/waiting?date=${date}`); } catch { /* the agenda is what matters */ }
  if (date !== state.date) return;
  $('waiting').hidden = !people.length;
  $('waiting-people').replaceChildren(...people.map((w) => {
    const digits = w.clientPhone.replace(/\D/g, '');
    const remove = el('button', { type: 'button', class: 'link small', 'aria-label': `Take ${w.clientName} off the list` }, 'Remove');
    remove.addEventListener('click', async () => {
      await api(`/api/admin/waiting/${w.id}`, { method: 'DELETE' });
      loadWaiting(date);
    });
    return el('li', {},
      el('span', { class: 'who-what' }, el('strong', {}, w.clientName), ' ',
        el('span', {}, `${w.service}${w.professional ? ` with ${w.professional}` : ''}`)),
      el('span', { class: 'side' },
        el('a', { class: 'phone', href: `tel:${w.clientPhone}` }, prettyPhone(w.clientPhone)),
        el('a', { class: 'link small', href: `https://wa.me/${digits.length <= 11 ? `55${digits}` : digits}`, target: '_blank', rel: 'noopener' }, 'WhatsApp'),
        remove));
  }));
}

// minutes from midnight of the agenda's day; time off that started before or ends after it is cut
function dayMinutes(iso, fallback) {
  if (iso.slice(0, 10) < state.date) return 0;
  if (iso.slice(0, 10) > state.date) return fallback;
  return minutes(iso.slice(11));
}

// bookings and time away in order, with the free time between them inside the working hours
function rows(bookings, ranges, away = []) {
  const out = [];
  const items = [
    ...bookings.map((b) => ({ start: minutes(b.starts.slice(11)), until: minutes(b.blockedUntil.slice(11)), row: { booking: b } })),
    ...away.map((t) => ({ start: dayMinutes(t.starts, 0), until: dayMinutes(t.ends, 24 * 60), row: { away: t } })),
  ].sort((a, b) => a.start - b.start);
  let i = 0;
  let awayUntil = -1;
  ranges.forEach((range, r) => {
    let cursor = minutes(range.start);
    const end = minutes(range.end);
    for (; i < items.length && items[i].start < end; i++) {
      const item = items[i];
      const start = Math.max(item.start, cursor);
      if (start - cursor >= 15) out.push({ free: true, from: cursor, to: start });
      // a booking made before the time away was set: the staff has to call that client
      if (item.row.booking && item.start < awayUntil) item.row.clash = true;
      if (item.row.away) awayUntil = Math.max(awayUntil, item.until);
      out.push(item.row);
      cursor = Math.max(cursor, item.until);
    }
    if (end - cursor >= 15) out.push({ free: true, from: cursor, to: end });
    // the gap until the next range of the day is the break
    if (r < ranges.length - 1) out.push({ gap: true, from: end, to: minutes(ranges[r + 1].start) });
  });
  // a booking outside the regular hours (special date) still shows up
  for (; i < items.length; i++) out.push(items[i].row);
  return out;
}

const team = () => state.business.professionals;

// with more than one barber the agenda can show one of them, with their free gaps, or everyone
function renderAgendaWho() {
  const box = $('agenda-who');
  box.hidden = team().length < 2;
  if (box.hidden) return;
  const pick = (pro, label) => {
    const button = el('button', {
      type: 'button', role: 'radio', class: 'who-pick',
      'aria-checked': String((state.pro?.id ?? null) === (pro?.id ?? null)),
    }, label);
    button.addEventListener('click', () => { state.pro = pro; loadDay(state.date); });
    return button;
  };
  box.replaceChildren(pick(null, 'Everyone'), ...team().map((p) => pick(p, p.name)));
}

function render(everything, day) {
  renderAgendaWho();
  const ranges = day ? day.ranges : state.business.hours[weekdayOf(state.date)] || [];
  const mine = (id) => !state.pro || id == null || id === state.pro.id;
  const all = everything.filter((b) => mine(b.professionalId));
  const away = (day ? day.timeOff : []).filter((t) => mine(t.professionalId));
  const list = $('agenda');
  const bookings = all.filter((b) => b.status === 'CONFIRMED');
  const cancelled = all.filter((b) => b.status === 'CANCELLED');
  // free gaps only mean something for one person's day
  const gaps = !state.pro && team().length > 1 ? false : true;

  if (!ranges.length && !all.length) {
    $('agenda-summary').textContent = '';
    list.replaceChildren(el('li', { class: 'empty' }, 'Closed on this day.'));
    return;
  }

  const booked = bookings.reduce((sum, b) => sum + minutes(b.ends.slice(11)) - minutes(b.starts.slice(11)), 0);
  const open = ranges.reduce((sum, r) => sum + minutes(r.end) - minutes(r.start), 0) * (state.pro ? 1 : Math.max(1, team().length));
  $('agenda-summary').textContent = bookings.length
    ? `${bookings.length} booking${bookings.length === 1 ? '' : 's'} · ${open ? Math.round((100 * booked) / open) : 0}% of the day booked`
    : 'Nothing booked yet.';
  if (cancelled.length) $('agenda-summary').textContent += ` · ${cancelled.length} cancelled`;

  list.replaceChildren(...rows(bookings, ranges, away).filter((row) => gaps || !row.free).map((row) => {
    if (row.away) {
      const from = row.away.starts.slice(0, 10) < state.date ? 'Earlier' : hhmm(row.away.starts.slice(11));
      const until = row.away.ends.slice(0, 10) > state.date ? 'the end of the day' : hhmm(row.away.ends.slice(11));
      return el('li', { class: 'away-row' },
        el('span', { class: 'when' }, from),
        el('span', {}, `${row.away.professional ? `${row.away.professional} away` : 'Closed'} until ${until}${row.away.reason ? ` · ${row.away.reason}` : ''}`));
    }
    if (row.gap) {
      return el('li', { class: 'break' }, el('span', { class: 'when' }), el('span', {}, `Break, ${clock(row.from)} to ${clock(row.to)}`));
    }
    if (row.free) {
      return el('li', { class: 'free-row' },
        el('span', { class: 'when' }, clock(row.from)),
        el('span', {}, `Free until ${clock(row.to)}`));
    }
    const li = bookingRow(row.booking);
    if (row.clash) li.querySelector('.who-what').append(el('span', { class: 'cancelled-stamp' }, 'During your time away, call to move it'));
    return li;
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
  const with_ = team().length > 1 && !state.pro ? ` · with ${b.professionalName}` : '';
  const who = el('span', { class: 'who-what' }, el('b', {}, b.clientName), el('span', {}, `${b.serviceName}${with_}`));
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
    // before the time: remind or cancel; after it: say whether they came
    const upcoming = `${b.starts}` > state.business.now;
    side.append(el('span', { class: 'row-actions' }, upcoming ? remindLink(b, phone) : noShowButton(b), upcoming ? cancel : null));
  }
  return el('li', { class: isCancelled ? 'booking-row is-cancelled' : 'booking-row' },
    el('span', { class: 'when' }, hhmm(b.starts.slice(11)), el('small', {}, hhmm(b.ends.slice(11)))),
    who, side);
}

// after the time: did they come? A no-show still took the time, it just didn't happen
function noShowButton(b) {
  const button = el('button', { type: 'button', class: b.noShow ? 'link small no-show is-on' : 'link small no-show', 'aria-pressed': String(b.noShow) },
    b.noShow ? "Didn't come" : 'Mark as no-show');
  button.addEventListener('click', async () => {
    try {
      await api(`/api/admin/bookings/${b.id}/no-show`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ noShow: !b.noShow }),
      });
    } catch (err) {
      if (err.status === 401) return show('login');
    }
    loadDay(state.date);
  });
  return button;
}

// the month in numbers: a few sentences, the figures, and how it splits
const money = (cents) => (cents / 100).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL', maximumFractionDigits: 0 });
const SHORT_DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const WEEKDAYS_LONG = ['Mondays', 'Tuesdays', 'Wednesdays', 'Thursdays', 'Fridays', 'Saturdays', 'Sundays'];

function shiftMonth(month, by) {
  const [y, m] = month.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1 + by, 1)).toISOString().slice(0, 7);
}

function monthStory(r, title) {
  const t = r.totals;
  if (!t.done && !t.coming) return `Nothing booked in ${title}.`;
  const story = [`${t.done} ${t.done === 1 ? 'client came' : 'clients came'}${t.coming ? `, and ${t.coming} more ${t.coming === 1 ? 'is' : 'are'} booked` : ''}.`];
  // comparing money only makes sense once the month is over
  if (r.previous.done && !t.coming) {
    const diff = t.earnedCents - r.previous.earnedCents;
    story.push(diff >= 0 ? `${money(diff)} more than the month before.` : `${money(-diff)} less than the month before.`);
  }
  if (t.noShows) story.push(`${t.noShows} didn't show up.`);
  const busiest = r.weekdays.reduce((a, b) => (b.done > a.done ? b : a), r.weekdays[0]);
  if (busiest.done) story.push(`${WEEKDAYS_LONG[busiest.value - 1]} were the busiest.`);
  return story.join(' ');
}

async function loadMonth(month) {
  state.month = month;
  const title = new Date(`${month}-01T12:00:00Z`).toLocaleDateString('en-US', { timeZone: 'UTC', month: 'long', year: 'numeric' });
  $('month-title').textContent = title;
  $('month-next').disabled = month >= state.today.slice(0, 7);
  let r;
  try {
    r = await api(`/api/admin/report?month=${month}`);
  } catch (err) {
    if (err.status === 401) return show('login');
    $('month-story').textContent = err.message;
    return;
  }
  if (state.month !== month) return;
  const t = r.totals;
  $('month-story').textContent = monthStory(r, title);
  $('month-figures').replaceChildren(...[
    ['made', money(t.earnedCents)], ['came', t.done], ['still booked', t.coming], ['no-shows', t.noShows],
    ['cancelled by clients', t.cancelledByClient], ['cancelled by you', t.cancelledByStaff],
  ].map(([label, value]) => el('div', {}, el('dt', {}, label), el('dd', {}, String(value)))));

  const lines = (id, rows) => {
    const top = Math.max(1, ...rows.map((x) => x.earnedCents));
    $(id).replaceChildren(...(rows.length ? rows.map((x) => {
      const bar = el('span', { class: 'bar' });
      bar.style.width = `${(100 * x.earnedCents) / top}%`;
      return el('li', {}, el('span', {}, x.name), el('span', { class: 'figure' }, `${x.done} · ${money(x.earnedCents)}`),
        el('span', { class: 'track', 'aria-hidden': 'true' }, bar));
    }) : [el('li', { class: 'empty' }, 'Nobody yet')]));
  };
  lines('month-people', r.people);
  lines('month-services', r.services);

  const columns = (id, rows, label) => {
    const top = Math.max(1, ...rows.map((x) => x.done));
    $(id).replaceChildren(...rows.map((x) => {
      const li = el('li', { title: `${label(x.value)}: ${x.done}` },
        el('span', { class: 'col', 'aria-hidden': 'true' }), el('span', { class: 'col-label' }, label(x.value)),
        el('span', { class: 'sr-only' }, `: ${x.done}`));
      li.style.setProperty('--h', `${x.done ? Math.max(6, (100 * x.done) / top) : 2}%`);
      return li;
    }));
  };
  columns('month-weekdays', r.weekdays, (d) => SHORT_DAYS[d - 1]);
  columns('month-hours', r.hours, (h) => `${h}h`);
}

$('month-prev').addEventListener('click', () => loadMonth(shiftMonth(state.month, -1)));
$('month-next').addEventListener('click', () => loadMonth(shiftMonth(state.month, 1)));

// WhatsApp opens with the reminder already written; the agenda marks it as sent, so nobody
// at the desk sends it twice. Nothing goes out by itself: the staff presses send.
function reminderText(b) {
  const day = b.starts.slice(0, 10);
  const when = day === state.today ? 'today' : day === shift(state.today, 1) ? 'tomorrow' : `on ${longDate(day)}`;
  return `Hi ${b.clientName.split(' ')[0]}! A reminder from ${state.business.name}: ${b.serviceName} with ${b.professionalName} ${when} at ${hhmm(b.starts.slice(11))}, ${state.business.address}. If you can't make it, just reply here.`;
}

function remindLink(b, phone) {
  const digits = phone.replace(/\D/g, '');
  const to = digits.length <= 11 ? `55${digits}` : digits;
  const link = el('a', {
    class: b.remindedAt ? 'link small reminded' : 'link small',
    href: `https://wa.me/${to}?text=${encodeURIComponent(reminderText(b))}`,
    target: '_blank', rel: 'noopener',
    title: b.remindedAt ? `Reminder sent at ${hhmm(b.remindedAt.slice(11))}` : 'Opens WhatsApp with the reminder written',
  }, b.remindedAt ? `Reminded ${hhmm(b.remindedAt.slice(11))}` : 'Remind');
  link.addEventListener('click', () => {
    api(`/api/admin/bookings/${b.id}/reminded`, { method: 'POST' })
      .then(() => { link.textContent = 'Reminded'; link.classList.add('reminded'); })
      .catch(() => {});
  });
  return link;
}

$('prev').addEventListener('click', () => loadDay(shift(state.date, -1)));
$('next').addEventListener('click', () => loadDay(shift(state.date, 1)));
$('today').addEventListener('click', () => loadDay(state.today));

async function start() {
  state.business = await api('/api/business');
  state.today = state.business.now.slice(0, 10);
  $('business-name').textContent = state.business.name;
  // the public demo hands out its login, so whoever opens it can look around
  const demo = state.business.demoLogin;
  if (demo) {
    $('demo-login').replaceChildren('This is a demo. Log in with ', el('b', {}, demo.email), ' and ', el('b', {}, demo.password), '.');
    $('demo-login').hidden = false;
    document.querySelector('#login input[name=email]').value = demo.email;
  }
  document.title = `Staff · ${state.business.name}`;
  try {
    await openAgenda(await api('/api/auth/me'));
  } catch {
    show('login');
  }
}

start();

// The pickers that work like radio buttons (service, who, whose agenda) also move with the
// arrow keys, the way a screen reader user expects a radio group to.
document.addEventListener('keydown', (e) => {
  const step = { ArrowDown: 1, ArrowRight: 1, ArrowUp: -1, ArrowLeft: -1 }[e.key];
  const radio = step && e.target.closest?.('[role="radio"]');
  const group = radio && radio.closest('[role="radiogroup"]');
  if (!group) return;
  const all = [...group.querySelectorAll('[role="radio"]:not([disabled])')];
  const next = all[(all.indexOf(radio) + step + all.length) % all.length];
  e.preventDefault();
  next.focus();
  next.click();
});
