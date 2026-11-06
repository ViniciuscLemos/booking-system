// special dates and time away. Uses $, api, el, show, longDate and state from admin.js

const shortDate = (iso) => parseDate(iso).toLocaleDateString('en-US', { timeZone: 'UTC', weekday: 'short', month: 'short', day: 'numeric' });

async function loadDaysOff() {
  // who can be away: the whole place, or one person
  const who = $('away-who');
  const picked = who.value;
  who.replaceChildren(el('option', { value: '' }, 'the whole place'),
    ...state.business.professionals.map((p) => el('option', { value: String(p.id) }, p.name)));
  who.value = picked;
  let data;
  try {
    data = await api('/api/admin/exceptions');
  } catch (err) {
    if (err.status === 401) show('login');
    return;
  }

  $('special-list').replaceChildren(...(data.dates.length ? data.dates.map((d) => listRow(
    shortDate(d.date),
    d.closed ? 'Closed all day' : `Open ${d.ranges.map((r) => `${hhmm(r.start)}–${hhmm(r.end)}`).join(', ')}`,
    `Back to the usual ${longDate(d.date).split(',')[0]} hours`,
    () => api(`/api/admin/dates/${d.date}`, { method: 'DELETE' }),
  )) : [el('li', { class: 'empty' }, 'Nothing special coming up, the usual week applies.')]));

  $('away-list').replaceChildren(...(data.timeOff.length ? data.timeOff.map((t) => listRow(
    shortDate(t.starts.slice(0, 10)),
    `${t.professional || 'Everyone'}, ${hhmm(t.starts.slice(11))}–${t.ends.slice(0, 10) === t.starts.slice(0, 10) ? hhmm(t.ends.slice(11)) : `${shortDate(t.ends.slice(0, 10))} ${hhmm(t.ends.slice(11))}`}${t.reason ? ` · ${t.reason}` : ''}`,
    'Remove this time away',
    () => api(`/api/admin/time-off/${t.id}`, { method: 'DELETE' }),
  )) : [el('li', { class: 'empty' }, 'No time away coming up.')]));
}

function listRow(when, what, removeLabel, remove) {
  const button = el('button', { type: 'button', class: 'link small', 'aria-label': removeLabel }, 'Remove');
  button.addEventListener('click', async () => {
    button.disabled = true;
    try {
      await remove();
    } catch (err) {
      if (err.status === 401) return show('login');
    }
    await loadDaysOff();
  });
  return el('li', {}, el('b', {}, when), el('span', { class: 'what' }, what), button);
}

// bookings already made aren't moved, so tell the staff how many are in the way
async function bookingsInTheWay(date, from, to) {
  const all = await api(`/api/admin/bookings?date=${date}`).catch(() => []);
  return all.filter((b) => b.status === 'CONFIRMED'
    && (!from || (b.starts.slice(11, 16) < to && b.ends.slice(11, 16) > from))).length;
}

function noteFor(count, date) {
  if (!count) return 'Saved.';
  return `Saved. ${count} booking${count === 1 ? ' is' : 's are'} already in that time on ${shortDate(date)}, see the agenda to call ${count === 1 ? 'that client' : 'them'}.`;
}

for (const input of document.querySelectorAll('#special-form input[name=kind]')) {
  input.addEventListener('change', () => {
    $('special-range').hidden = document.querySelector('#special-form input[name=kind]:checked').value !== 'hours';
  });
}

$('special-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const form = event.target;
  const error = $('special-error');
  error.hidden = true;
  $('special-note').hidden = true;
  const date = form.date.value;
  const open = form.kind.value === 'hours';
  if (!date) return fail(error, 'Pick a date.');
  if (date < state.today) return fail(error, 'That date has already passed.');
  if (open && !(form.from.value && form.to.value && form.from.value < form.to.value)) {
    return fail(error, 'The opening time has to be before the closing time.');
  }
  try {
    await api(`/api/admin/dates/${date}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(open ? [{ start: form.from.value, end: form.to.value }] : []),
    });
  } catch (err) {
    if (err.status === 401) return show('login');
    return fail(error, err.message);
  }
  // a closed day has everything in the way; open hours only what falls outside them
  const total = await bookingsInTheWay(date);
  const inside = open ? await bookingsInTheWay(date, form.from.value, form.to.value) : 0;
  $('special-note').textContent = noteFor(total - inside, date);
  $('special-note').hidden = false;
  form.reset();
  $('special-range').hidden = true;
  await loadDaysOff();
});

$('away-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const form = event.target;
  const error = $('away-error');
  error.hidden = true;
  $('away-note').hidden = true;
  const date = form.date.value;
  if (!date) return fail(error, 'Pick a date.');
  if (!(form.from.value && form.to.value && form.from.value < form.to.value)) {
    return fail(error, 'The time away has to end after it starts.');
  }
  try {
    await api('/api/admin/time-off', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        starts: `${date}T${form.from.value}`, ends: `${date}T${form.to.value}`, reason: form.reason.value.trim() || null,
        professionalId: form.who.value ? Number(form.who.value) : null,
      }),
    });
  } catch (err) {
    if (err.status === 401) return show('login');
    return fail(error, err.message);
  }
  $('away-note').textContent = noteFor(await bookingsInTheWay(date, form.from.value, form.to.value), date);
  $('away-note').hidden = false;
  form.reason.value = '';
  await loadDaysOff();
});

function fail(box, message) {
  box.textContent = message;
  box.hidden = false;
}
