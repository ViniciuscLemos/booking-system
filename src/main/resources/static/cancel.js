// the page the cancel link opens. The token is after the #, so it stays in the browser
// until this script sends it to the API

const $ = (id) => document.getElementById(id);
const token = location.hash.slice(1);

function parseDate(iso) {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d));
}
const day = (iso, opts) => parseDate(iso.slice(0, 10)).toLocaleDateString('en-US', { timeZone: 'UTC', ...opts });
const time = (iso) => iso.slice(11, 16);

async function api(path, options) {
  const res = await fetch(path, options);
  const body = await res.json().catch(() => null);
  if (!res.ok) throw Object.assign(new Error(body?.error || 'Something went wrong'), { status: res.status });
  return body;
}

function show(booking) {
  const cancelled = booking.status === 'CANCELLED';
  $('kicker').textContent = cancelled ? 'Cancelled' : 'Your booking';
  $('kicker').className = cancelled ? 'kicker cancelled-stamp' : 'kicker';
  $('title').textContent = cancelled
    ? 'This booking is cancelled.'
    : `${booking.service}, ${day(booking.starts, { weekday: 'long' })} at ${time(booking.starts)}`;

  const rows = [
    ['Name', booking.clientName],
    ['Service', booking.service],
    ['When', `${day(booking.starts, { weekday: 'long', month: 'long', day: 'numeric' })}, ${time(booking.starts)}–${time(booking.ends)}`],
    ['Booking', `#${booking.id}`],
  ];
  $('receipt').replaceChildren(...rows.flatMap(([k, v]) => {
    const dt = document.createElement('dt');
    const dd = document.createElement('dd');
    dt.textContent = k;
    dd.textContent = v;
    return [dt, dd];
  }));
  $('receipt').hidden = false;

  const until = `${day(booking.cancelUntil, { weekday: 'short', month: 'short', day: 'numeric' })} at ${time(booking.cancelUntil)}`;
  if (cancelled) {
    $('note').textContent = 'The time is free for someone else now.';
  } else if (booking.canCancel) {
    $('note').textContent = `You can cancel online until ${until}.`;
  } else {
    $('note').textContent = `Online cancelling closed on ${until}. If you can't make it, please give us a call.`;
  }
  $('cancel').hidden = !booking.canCancel;
  $('book').textContent = cancelled ? 'Book another time' : 'Book a time';
}

$('cancel').addEventListener('click', async () => {
  const button = $('cancel');
  // a second click confirms, so a stray tap doesn't cancel anything
  if (!button.dataset.sure) {
    button.dataset.sure = '1';
    button.textContent = 'Yes, cancel it';
    return;
  }
  button.disabled = true;
  try {
    show(await api(`/api/bookings/by-token/${encodeURIComponent(token)}/cancel`, { method: 'POST' }));
    $('view').focus();
  } catch (err) {
    $('error').textContent = err.message;
    $('error').hidden = false;
    button.hidden = true;
  } finally {
    button.disabled = false;
  }
});

async function start() {
  api('/api/business').then((b) => {
    $('business-name').textContent = b.name;
    $('business-address').textContent = b.address;
    document.title = `Your booking · ${b.name}`;
  }).catch(() => {});

  if (!token) {
    $('title').textContent = 'This link is missing a part.';
    $('note').textContent = 'Open the whole link you got when you booked.';
    return;
  }
  try {
    show(await api(`/api/bookings/by-token/${encodeURIComponent(token)}`));
  } catch (err) {
    $('title').textContent = err.status === 404 ? "This link doesn't work." : err.message;
    $('note').textContent = 'Check that you opened the whole link, or give us a call.';
  }
}

start();
