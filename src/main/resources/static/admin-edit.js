// services and opening hours on the staff page. Uses $, api, el and show from admin.js

const DAY_NAMES = { MONDAY: 'Monday', TUESDAY: 'Tuesday', WEDNESDAY: 'Wednesday', THURSDAY: 'Thursday',
  FRIDAY: 'Friday', SATURDAY: 'Saturday', SUNDAY: 'Sunday' };

const reais = (cents) => new Intl.NumberFormat('pt-BR', {
  style: 'currency', currency: 'BRL', minimumFractionDigits: cents % 100 ? 2 : 0,
}).format(cents / 100);

// "45", "45,50" or "45.50" -> 4550; null when it isn't a price
function parseReais(text) {
  const clean = text.trim().replace(/^R\$\s*/, '').replace(',', '.');
  if (!/^\d+(\.\d{1,2})?$/.test(clean)) return null;
  return Math.round(Number(clean) * 100);
}

function handle401(err) {
  if (err.status === 401) {
    show('login');
    return true;
  }
  return false;
}

// services

let services = [];

async function loadServices() {
  try {
    services = await api('/api/admin/services');
  } catch (err) {
    if (!handle401(err)) $('services-admin').replaceChildren(el('li', { class: 'empty' }, err.message));
    return;
  }
  renderServices();
  loadTeam();
}

// the team: add someone, rename, turn off and back on

async function loadTeam() {
  let people;
  try {
    people = await api('/api/admin/professionals');
  } catch (err) {
    handle401(err);
    return;
  }
  const active = services.filter((s) => s.active);
  $('team').replaceChildren(...people.flatMap((p) => {
    const does = p.services.length ? active.filter((s) => p.services.includes(s.id)).map((s) => s.name).join(', ') : 'every service';
    const pick = el('button', { type: 'button', class: 'link small' }, 'Services');
    const choices = el('li', { class: 'team-services', hidden: true },
      ...active.map((s) => {
        const box = el('input', { type: 'checkbox', value: String(s.id) });
        box.checked = !p.services.length || p.services.includes(s.id);
        return el('label', { class: 'check' }, box, ` ${s.name}`);
      }));
    const save = el('button', { type: 'button', class: 'confirm small-btn' }, 'Save');
    save.addEventListener('click', async () => {
      const ids = [...choices.querySelectorAll('input:checked')].map((b) => Number(b.value));
      // every box ticked is the same as "does everything": stored as an empty list
      const body = ids.length === active.length ? [] : ids;
      if (!body.length && ids.length === 0) return fail($('team-error'), 'Tick at least one service, or turn this person off.');
      try {
        await api(`/api/admin/professionals/${p.id}/services`, {
          method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
        });
      } catch (err) {
        if (!handle401(err)) fail($('team-error'), err.message);
        return;
      }
      state.business = await api('/api/business');
      loadTeam();
    });
    choices.append(save);
    pick.addEventListener('click', () => { choices.hidden = !choices.hidden; hours.hidden = true; });
    const hours = hoursPanel(p);
    const when = el('button', { type: 'button', class: 'link small' }, 'Hours');
    when.addEventListener('click', () => { hours.hidden = !hours.hidden; choices.hidden = true; });
    const row = teamRow(p, [does, ownHoursText(p.hours)].filter(Boolean).join(' · '), pick, when);
    return [row, choices, hours];
  }));
}

const SHORT_DAY = { MONDAY: 'Mon', TUESDAY: 'Tue', WEDNESDAY: 'Wed', THURSDAY: 'Thu', FRIDAY: 'Fri', SATURDAY: 'Sat', SUNDAY: 'Sun' };
const hm = (t) => t.slice(0, 5);

// "Tue, Thu 13:00-18:00 · Sat 09:00-13:00": days with the same hours go together
function ownHoursText(hours) {
  if (!hours) return '';
  const groups = new Map();
  for (const day of WEEKDAYS) {
    const ranges = hours[day];
    if (!ranges) continue;
    const key = ranges.map((r) => `${hm(r.start)}–${hm(r.end)}`).join(', ');
    groups.set(key, [...(groups.get(key) || []), SHORT_DAY[day]]);
  }
  return [...groups].map(([key, days]) => `${days.join(', ')} ${key}`).join(' · ');
}

// one stretch per day is enough for a person's week: the place's lunch break still applies on top
function hoursPanel(p) {
  const open = WEEKDAYS.filter((day) => (state.business.hours[day] || []).length);
  const rows = open.map((day) => {
    const place = state.business.hours[day];
    const theirs = p.hours ? p.hours[day] : place;
    const works = el('input', { type: 'checkbox' });
    works.checked = !!theirs;
    const from = el('input', { type: 'time', value: hm((theirs || place)[0].start), 'aria-label': `${p.name} on ${DAY_NAMES[day]} from` });
    const until = el('input', { type: 'time', value: hm((theirs || place).at(-1).end), 'aria-label': `${p.name} on ${DAY_NAMES[day]} until` });
    const sync = () => { from.disabled = until.disabled = !works.checked; };
    works.addEventListener('change', sync);
    sync();
    const row = el('div', { class: 'own-day' }, el('label', { class: 'check' }, works, ` ${DAY_NAMES[day]}`), from,
      el('span', { 'aria-hidden': 'true' }, 'to'), until);
    row.read = () => ({ day, works: works.checked, start: from.value, end: until.value, place });
    return row;
  });

  const save = el('button', { type: 'button', class: 'confirm small-btn' }, 'Save hours');
  const reset = el('button', { type: 'button', class: 'link small' }, 'Whenever the place is open');
  const panel = el('li', { class: 'team-hours', hidden: true },
    el('p', { class: 'muted' }, `The days ${p.name} works, and from when to when. Outside the place's hours nobody is booked anyway.`),
    ...rows, el('div', { class: 'own-actions' }, save, reset));

  const send = async (week) => {
    $('team-error').hidden = true;
    try {
      await api(`/api/admin/professionals/${p.id}/hours`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(week),
      });
    } catch (err) {
      if (!handle401(err)) fail($('team-error'), err.message);
      return;
    }
    loadTeam();
  };
  save.addEventListener('click', () => {
    const days = rows.map((r) => r.read());
    const bad = days.find((d) => d.works && (!d.start || !d.end || d.start >= d.end));
    if (bad) return fail($('team-error'), `${DAY_NAMES[bad.day]}: the hours have to end after they start.`);
    if (!days.some((d) => d.works)) return fail($('team-error'), `Tick at least one day, or turn ${p.name} off.`);
    // every day ticked with the place's own hours is the same as having none of their own
    const same = days.every((d) => d.works && d.start === hm(d.place[0].start) && d.end === hm(d.place.at(-1).end));
    const week = {};
    if (!same) for (const d of days) if (d.works) week[d.day] = [{ start: d.start, end: d.end }];
    send(week);
  });
  reset.addEventListener('click', () => send({}));
  return panel;
}

function teamRow(p, does, pick, when) {
  const toggle = el('button', { type: 'button', class: 'link small' }, p.active ? 'Turn off' : 'Turn back on');
  toggle.addEventListener('click', () => saveMember(p.id, { name: p.name, active: !p.active }));
  const rename = el('button', { type: 'button', class: 'link small' }, 'Rename');
  rename.addEventListener('click', () => {
    const name = prompt('New name', p.name);
    if (name && name.trim() && name.trim() !== p.name) saveMember(p.id, { name: name.trim(), active: p.active });
  });
  return el('li', { class: p.active ? '' : 'off' },
    el('span', { class: 'when' }, p.name),
    el('span', { class: 'muted' }, p.active ? does : 'turned off'),
    el('span', { class: 'side' }, pick, when, rename, toggle));
}

async function saveMember(id, body) {
  $('team-error').hidden = true;
  try {
    await api(`/api/admin/professionals/${id}`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    });
  } catch (err) {
    if (!handle401(err)) fail($('team-error'), err.message);
    return;
  }
  // the booking page and the agenda read the team from here
  state.business = await api('/api/business');
  loadTeam();
}

$('team-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const name = event.target.name.value.trim();
  if (!name) return;
  $('team-error').hidden = true;
  try {
    await api('/api/admin/professionals', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name }),
    });
  } catch (err) {
    if (!handle401(err)) fail($('team-error'), err.message);
    return;
  }
  event.target.reset();
  state.business = await api('/api/business');
  loadTeam();
});

function renderServices(editingId) {
  $('services-admin').replaceChildren(...services.map((s) => (s.id === editingId ? serviceForm(s) : serviceLine(s))));
}

function serviceLine(s) {
  const edit = el('button', { type: 'button', class: 'link small' }, 'Edit');
  edit.addEventListener('click', () => renderServices(s.id));
  const buffer = s.bufferMin ? ` + ${s.bufferMin} min clean-up` : '';
  return el('li', {},
    el('div', { class: 'service-line' },
      el('span', { class: s.active ? '' : 'off' }, el('b', {}, s.name), s.active ? null : el('span', { class: 'tag' }, 'hidden')),
      el('span', { class: 'meta' }, `${s.durationMin} min${buffer} · ${reais(s.priceCents)}`),
      edit));
}

function field(label, input, cls) {
  return el('label', cls ? { class: cls } : {}, label, input);
}

function serviceForm(s) {
  const isNew = !s.id;
  const name = el('input', { name: 'name', maxlength: '80', value: s.name || '', required: true });
  const description = el('input', { name: 'description', maxlength: '300', value: s.description || '' });
  const duration = el('input', { name: 'duration', type: 'number', min: '5', max: '480', step: '5', value: s.durationMin ?? 30 });
  const buffer = el('input', { name: 'buffer', type: 'number', min: '0', max: '120', step: '5', value: s.bufferMin ?? 0 });
  const price = el('input', { name: 'price', inputmode: 'decimal', value: s.priceCents != null ? String(s.priceCents / 100).replace('.', ',') : '' });
  const active = el('input', { name: 'active', type: 'checkbox', checked: s.active !== false });
  const error = el('p', { class: 'error wide', role: 'alert', hidden: true });
  const save = el('button', { type: 'submit', class: 'confirm' }, isNew ? 'Add service' : 'Save');
  const cancel = el('button', { type: 'button', class: 'link' }, 'Cancel');

  const form = el('form', { class: 'edit-form', novalidate: true },
    field('Name', name, 'wide'),
    field('Description (optional)', description, 'wide'),
    field('Duration (min)', duration),
    field('Clean-up after (min)', buffer),
    field('Price (R$)', price),
    el('label', { class: 'check wide' }, active, 'Shown on the booking page'),
    error,
    el('div', { class: 'actions' }, save, cancel));

  cancel.addEventListener('click', () => (isNew ? form.closest('li').remove() : renderServices()));
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const cents = parseReais(price.value);
    const problems = [];
    if (!name.value.trim()) problems.push('a name');
    if (cents === null) problems.push('a price like 45 or 45,50');
    if (problems.length) {
      error.textContent = `Please add ${problems.join(' and ')}.`;
      error.hidden = false;
      return;
    }
    save.disabled = true;
    try {
      await api(isNew ? '/api/admin/services' : `/api/admin/services/${s.id}`, {
        method: isNew ? 'POST' : 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: name.value.trim(),
          description: description.value.trim() || null,
          durationMin: Number(duration.value),
          bufferMin: Number(buffer.value || 0),
          priceCents: cents,
          active: active.checked,
        }),
      });
      await loadServices();
    } catch (err) {
      if (handle401(err)) return;
      error.textContent = err.problems?.length
        ? err.problems.map((p) => `${p.code}: ${p.message}`).join('. ')
        : err.message;
      error.hidden = false;
    } finally {
      save.disabled = false;
    }
  });

  setTimeout(() => name.focus());
  return el('li', {}, form);
}

$('add-service').addEventListener('click', () => {
  renderServices();
  $('services-admin').append(serviceForm({ active: true }));
});

// opening hours

async function loadHours() {
  try {
    // fresh from the server, someone may have changed them in another tab
    state.business = await api('/api/business');
  } catch (err) {
    handle401(err);
    return;
  }
  $('hours-saved').hidden = true;
  $('hours-error').hidden = true;
  $('week').replaceChildren(...WEEKDAYS.map((day) => dayRow(day, state.business.hours[day] || [])));
}

function dayRow(day, ranges) {
  const list = el('div', { class: 'ranges' });
  const add = el('button', { type: 'button', class: 'link small' }, 'Add hours');

  const refresh = () => {
    const closed = !list.querySelector('.range');
    list.querySelector('.closed-note')?.remove();
    if (closed) list.prepend(el('span', { class: 'closed-note' }, 'Closed'));
    add.textContent = closed ? 'Open this day' : 'Add another stretch';
  };
  const addRange = (start, end) => {
    const remove = el('button', { type: 'button', class: 'link small remove', 'aria-label': `Remove these hours on ${DAY_NAMES[day]}` }, 'Remove');
    const row = el('div', { class: 'range' },
      el('input', { type: 'time', value: start, 'aria-label': `${DAY_NAMES[day]} from`, required: true }),
      el('span', { 'aria-hidden': 'true' }, 'to'),
      el('input', { type: 'time', value: end, 'aria-label': `${DAY_NAMES[day]} until`, required: true }),
      remove);
    remove.addEventListener('click', () => { row.remove(); refresh(); });
    list.insertBefore(row, add);
    refresh();
  };

  list.append(add);
  add.addEventListener('click', () => {
    // a new stretch starts where the last one ended, or at 09:00
    const ends = [...list.querySelectorAll('.range input:last-of-type')].map((i) => i.value).filter(Boolean);
    const from = ends.at(-1) || '09:00';
    addRange(from, from < '17:00' ? addHour(from, 3) : '23:00');
  });
  for (const r of ranges) addRange(r.start.slice(0, 5), r.end.slice(0, 5));
  refresh();

  return el('li', { 'data-day': day }, el('span', { class: 'dayname' }, DAY_NAMES[day]), list);
}

function addHour(time, hours) {
  const h = Math.min(23, Number(time.slice(0, 2)) + hours);
  return `${String(h).padStart(2, '0')}:${time.slice(3, 5)}`;
}

$('hours-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const error = $('hours-error');
  error.hidden = true;
  $('hours-saved').hidden = true;

  const week = {};
  for (const li of $('week').children) {
    const day = li.dataset.day;
    const ranges = [...li.querySelectorAll('.range')].map((row) => {
      const [start, end] = row.querySelectorAll('input');
      return { start: start.value, end: end.value };
    });
    const bad = ranges.find((r) => !r.start || !r.end || r.start >= r.end);
    if (bad) {
      error.textContent = `${DAY_NAMES[day]}: each stretch has to end after it starts.`;
      error.hidden = false;
      return;
    }
    if (ranges.length) week[day] = ranges;
  }

  const button = event.target.querySelector('.confirm');
  button.disabled = true;
  try {
    await api('/api/admin/hours', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(week),
    });
    await loadHours();
    $('hours-saved').hidden = false;
  } catch (err) {
    if (handle401(err)) return;
    // the server refuses stretches that overlap on the same day
    error.textContent = /overlap/i.test(err.message) ? 'Two stretches on the same day overlap.' : err.message;
    error.hidden = false;
  } finally {
    button.disabled = false;
  }
});
