/* The page controller. Forms and buttons send requests to the Java server. */
'use strict';
const page = document.body.dataset.page; // Read data-page from the HTML body to decide which page controls to set up.
const $ = selector => document.querySelector(selector); // A shortcut for finding one page element; for example, $('#address') finds the address field.
const money = cents => new Intl.NumberFormat('en-SG', {style:'currency', currency:'SGD'}).format(cents / 100); // Convert cents into a displayed SGD price; 320 becomes $3.20.
let account = null;
let basket = {items:[]}; // Keep a local copy for display; the server still decides the real quantities and prices.

// escaping
// Keep database text from becoming executable HTML.
const escapeHtml = text => String(text).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
function notice(text, error = false) { // Display a message and use the error style when the second argument is true.
  $('#notice').textContent = text;
  $('#notice').classList.toggle('error', error);
}
// requesting
// No data means GET; supplied data becomes a POST form.
async function api(path, data) {
  const options = data === undefined ? {} : {method:'POST', body:new URLSearchParams(data)};
  const response = await fetch('/api/' + path, options);
  const result = await response.json(); // Wait for the server reply and convert its JSON text into a JavaScript value.
  if (!response.ok) throw new Error(result.error || 'Request failed.'); // fetch does not reject HTTP error codes itself, so handle them here.
  return result;
}
function cartCount() {
  $('#cart-count').textContent = basket.items.reduce((sum, item) => sum + item.quantity, 0); // Add quantities rather than product rows; two milks and one bread give a count of three.
}

let searchVersion = 0;
async function catalogue() {
  const version = ++searchVersion; // Give each search a number so a slower old response cannot replace newer results.
  const items = await api('groceries?q=' + encodeURIComponent($('#search').value));
  // searching
  // Ignore an older response when a newer search has already started.
  if (version !== searchVersion) return;
  $('#result-count').textContent = `${items.length} ${items.length === 1 ? 'item' : 'items'}`;
  $('#products').innerHTML = items.length ? items.map(item => {
    const expired = item.expiry_date < new Date().toISOString().slice(0,10); // Compare dates in YYYY-MM-DD order against the current UTC date.
    return `<article class="card"><img src="images/${escapeHtml(item.image)}" alt="Illustration of ${escapeHtml(item.item_name)}">
      <div class="card-body"><span class="category">${escapeHtml(item.category)}</span><h3>${escapeHtml(item.item_name)}</h3>
      <p class="meta">Expiry: ${item.expiry_date}<br>Quantity in stock: ${item.stock}</p>
      <span class="badge ${item.stock ? '' : 'out'}">${escapeHtml(item.availability)}</span>${expired ? ' <span class="badge out">Expired</span>' : ''}
      <div class="card-bottom"><span class="price">${money(item.price_cents)}</span><button data-add="${item.id}" ${!item.stock || expired ? 'disabled' : ''}>Add +</button></div></div></article>`;
  }).join('') : '<p class="empty">No groceries match your search.</p>';
}
// rendering cart
// Use server totals; quantity buttons submit the desired new count.
function renderCart() {
  cartCount();
  $('#cart-items').innerHTML = basket.items.length ? basket.items.map(item => `<div class="cart-row">
    <img src="images/${escapeHtml(item.image)}" alt=""><div><h3>${escapeHtml(item.item_name)}</h3><span class="muted">${money(item.price_cents)} per unit · ${item.stock} in stock</span></div>
    <div class="stepper"><button data-item="${item.id}" data-quantity="${item.quantity-1}" aria-label="Decrease ${escapeHtml(item.item_name)} quantity">−</button><span>${item.quantity}</span><button data-item="${item.id}" data-quantity="${item.quantity+1}" aria-label="Increase ${escapeHtml(item.item_name)} quantity" ${item.quantity >= item.stock ? 'disabled' : ''}>+</button></div>
    <div><strong>${money(item.price_cents*item.quantity)}</strong> <button class="secondary" data-item="${item.id}" data-quantity="0" aria-label="Remove ${escapeHtml(item.item_name)}">Remove</button></div></div>`).join('') : '<p class="empty">Your basket is empty. <a href="main-grocery-page.html">Browse groceries</a>.</p>';
  $('#subtotal').textContent = money(basket.subtotal);
  $('#fee').textContent = money(basket.fee);
  $('#total').textContent = money(basket.total);
  $('#checkout').disabled = !basket.items.length;
}
// summarising
// Preview selected fees locally; Java calculates the stored total independently.
function deliverySummary() {
  const mode = $('input[name="mode"]:checked').value;
  const tip = Number($('input[name="tip"]:checked').value); // Radio input values are text, so convert the selected tip to a number before adding it.
  const fee = basket.items.length ? basket.fee + (mode === 'priority' ? 110 : 0) : 0;
  $('#schedule-fields').classList.toggle('hidden', mode !== 'scheduled');
  $('#scheduled').required = mode === 'scheduled'; // Require a date only when scheduled delivery is selected.
  $('#delivery-subtotal').textContent = money(basket.subtotal);
  $('#delivery-fee').textContent = money(fee);
  $('#delivery-tip').textContent = money(basket.items.length ? tip : 0);
  $('#delivery-fee-label').textContent = mode[0].toUpperCase() + mode.slice(1) + ' delivery';
  $('#delivery-total').textContent = money(basket.subtotal + fee + (basket.items.length ? tip : 0));
  $('#place-order').disabled = !basket.items.length;
  $('#delivery-items').innerHTML = basket.items.length ? basket.items.map(item => `<div class="summary-item"><b>${item.quantity}×</b><span>${escapeHtml(item.item_name)}</span><strong>${money(item.quantity*item.price_cents)}</strong></div>`).join('') : '<p class="empty">Your basket is empty. <a href="main-grocery-page.html">Browse groceries</a>.</p>';
}
// checking session
// Anonymous visitors may browse; account actions also require server authorisation.
async function initialise() {
  try { account = await api('account'); basket = await api('cart'); } catch (error) {
    $('#account-link').href = 'login-and-register.html';
    if (page !== 'login' && page !== 'catalogue') {
      notice('Sign in to access this page. Use the Account link above.', true);
      $('#protected').classList.add('hidden'); return;
    }
  }
  cartCount();
  if (account) {
    $('#account-link').textContent = account.username;
    $('#logout').classList.remove('hidden');
  }
  $('#logout').addEventListener('click', async () => {
    try { await api('logout', {}); location.href='login-and-register.html'; } catch (error) { notice(error.message,true); }
  });
  if (page === 'login') {
    for (const name of ['login','register']) $('#' + name + '-form').addEventListener('submit', async event => {
      // submitting
      // Prevent page reload and repeat clicks while the form request is pending.
      event.preventDefault(); const button = event.target.querySelector('button'); button.disabled = true;
      try {
        const result = await api(name, Object.fromEntries(new FormData(event.target)));
        if (name === 'login') location.href='main-grocery-page.html';
        else { notice(result.message); event.target.reset(); $('#login-id').focus(); }
      } catch (error) { notice(error.message,true); } finally { button.disabled=false; }
    });
  }
  if (page === 'catalogue') {
    await catalogue();
    let timer;
    // Wait 150 ms after typing before requesting search results.
    $('#search').addEventListener('input', () => { clearTimeout(timer); timer=setTimeout(() => catalogue().catch(e => notice(e.message,true)),150); });
    // Delegate clicks to the container so replacement cards keep working.
    $('#products').addEventListener('click', async event => {
      const button = event.target.closest('[data-add]'); if (!button) return; // Find the Add button that caused the click, even if the click began inside it.
      if (!account) { location.href='login-and-register.html'; return; }
      button.disabled=true;
      try {
        const id=Number(button.dataset.add), item=basket.items.find(item => item.id === id);
        basket=await api('cart',{id,quantity:(item ? item.quantity : 0)+1}); cartCount(); notice('Item added to your basket.');
      } catch(error) { notice(error.message,true); } finally { button.disabled=false; }
    });
  }
  if (page === 'cart') {
    renderCart();
    $('#cart-items').addEventListener('click', async event => {
      const button=event.target.closest('[data-item]'); if (!button) return;
      button.disabled=true;
      try { basket=await api('cart',{id:button.dataset.item,quantity:button.dataset.quantity}); renderCart(); notice('Basket updated.'); }
      catch(error) { notice(error.message,true); button.disabled=false; }
    });
    $('#checkout').textContent = 'Continue to delivery';
    $('#checkout').addEventListener('click', () => { location.href='delivery-and-map.html'; });
  }
  if (page === 'delivery') {
    $('#address').value = account.address;
    deliverySummary();
    document.querySelectorAll('input[name="mode"],input[name="tip"]').forEach(input => input.addEventListener('change', deliverySummary));
    $('#save-address').addEventListener('click', async () => {
      if (!$('#address').reportValidity()) return; // Show the browser validation message and stop if the address is missing or invalid.
      try { const result=await api('address',{address:$('#address').value}); notice(result.message); }
      catch(error) { notice(error.message,true); }
    });
    $('#protected').addEventListener('submit', async event => {
      event.preventDefault();
      const mode=$('input[name="mode"]:checked').value;
      const data={address:$('#address').value, instructions:$('#instructions').value,
        leave_at_door:$('#leave-at-door').checked, mode, tip:$('input[name="tip"]:checked').value};
      if (mode === 'scheduled') {
        const time = new Date($('#scheduled').value);
        const delay = time.getTime()-Date.now(); // Find the delay in milliseconds; 1,800,000 ms is 30 minutes.
        if (!Number.isFinite(delay) || delay < 1800000 || delay > 604800000) {
          notice('Choose a delivery time 30 minutes to seven days ahead.',true); return;
        }
        data.scheduled=time.toISOString(); // Send the chosen local time as UTC so the server interprets the same moment.
      }
      $('#place-order').disabled=true;
      try {
        const order=await api('checkout',data);
        basket=await api('cart'); cartCount(); deliverySummary();
        notice(`Order #${order.id} placed. ${mode === 'scheduled' ? 'Scheduled for '+new Date(data.scheduled).toLocaleString() : 'Estimated delivery: '+order.minutes+' minutes'}. No payment was taken.`);
        window.scrollTo({top:0,behavior:'smooth'});
      } catch(error) { notice(error.message,true); $('#place-order').disabled=!basket.items.length; }
    });
  }
  if (page === 'account') {
    $('#username').textContent=account.username; $('#email').textContent=account.email;
    $('#saved-address').textContent=account.address || 'No delivery address saved.';
    const orders=await api('orders'); // Load this customer's receipts, including delivery choices saved at checkout.
    $('#orders').innerHTML=orders.length ? orders.map(order => `<article class="order"><h3>Order #${order.id} · ${money(order.total_cents)}</h3><p class="muted">${escapeHtml(order.placed_at)} UTC · ${order.scheduled ? escapeHtml(new Date(order.scheduled).toLocaleString()) : order.delivery_minutes + " minute estimate"}</p><p>${escapeHtml(order.address)}</p>${order.mode ? `<p class="muted">${escapeHtml(order.mode)} delivery · Tip ${money(order.tip_cents)}${order.leave_at_door ? " · Leave at the door" : ""}</p><p>${escapeHtml(order.instructions)}</p>` : ""}<p>${order.items.map(item => `${item.quantity} × ${escapeHtml(item.item_name)}`).join(', ')}</p></article>`).join('') : '<p class="muted">No purchases have been recorded.</p>';
  }
}
initialise().catch(error => notice(error.message,true));
