## Default Permission

Default permissions for the purchases plugin.

#### Granted Permissions

- Support probe (`is_supported`) and product catalog reads
  (`get_products`, `get_subscription_status`, `get_entitlements`).
- Store payment flows (`purchase`, `restore_purchases`) and the store's own
  subscription-management surface (`manage_subscriptions`).
- Purchase-update events (`register_listener`, `remove_listener`).

Note: these are Tauri IPC permissions. Actual purchases are additionally
gated by the OS payment sheet — nothing can be charged without the user
confirming in the store's own UI.

#### This default permission set includes the following:

- `allow-is-supported`
- `allow-get-products`
- `allow-purchase`
- `allow-restore-purchases`
- `allow-get-entitlements`
- `allow-get-subscription-status`
- `allow-manage-subscriptions`
- `allow-register-listener`
- `allow-remove-listener`

## Permission Table

<table>
<tr>
<th>Identifier</th>
<th>Description</th>
</tr>


<tr>
<td>

`purchases:allow-get-entitlements`

</td>
<td>

Enables the get_entitlements command without any pre-configured scope.

</td>
</tr>

<tr>
<td>

`purchases:deny-get-entitlements`

</td>
<td>

Denies the get_entitlements command without any pre-configured scope.

</td>
</tr>

<tr>
<td>

`purchases:allow-get-products`

</td>
<td>

Enables the get_products command without any pre-configured scope.

</td>
</tr>

<tr>
<td>

`purchases:deny-get-products`

</td>
<td>

Denies the get_products command without any pre-configured scope.

</td>
</tr>

<tr>
<td>

`purchases:allow-get-subscription-status`

</td>
<td>

Enables the get_subscription_status command without any pre-configured scope.

</td>
</tr>

<tr>
<td>

`purchases:deny-get-subscription-status`

</td>
<td>

Denies the get_subscription_status command without any pre-configured scope.

</td>
</tr>

<tr>
<td>

`purchases:allow-is-supported`

</td>
<td>

Enables the is_supported command without any pre-configured scope.

</td>
</tr>

<tr>
<td>

`purchases:deny-is-supported`

</td>
<td>

Denies the is_supported command without any pre-configured scope.

</td>
</tr>

<tr>
<td>

`purchases:allow-manage-subscriptions`

</td>
<td>

Enables the manage_subscriptions command without any pre-configured scope.

</td>
</tr>

<tr>
<td>

`purchases:deny-manage-subscriptions`

</td>
<td>

Denies the manage_subscriptions command without any pre-configured scope.

</td>
</tr>

<tr>
<td>

`purchases:allow-purchase`

</td>
<td>

Enables the purchase command without any pre-configured scope.

</td>
</tr>

<tr>
<td>

`purchases:deny-purchase`

</td>
<td>

Denies the purchase command without any pre-configured scope.

</td>
</tr>

<tr>
<td>

`purchases:allow-register-listener`

</td>
<td>

Enables the register_listener command without any pre-configured scope.

</td>
</tr>

<tr>
<td>

`purchases:deny-register-listener`

</td>
<td>

Denies the register_listener command without any pre-configured scope.

</td>
</tr>

<tr>
<td>

`purchases:allow-remove-listener`

</td>
<td>

Enables the remove_listener command without any pre-configured scope.

</td>
</tr>

<tr>
<td>

`purchases:deny-remove-listener`

</td>
<td>

Denies the remove_listener command without any pre-configured scope.

</td>
</tr>

<tr>
<td>

`purchases:allow-restore-purchases`

</td>
<td>

Enables the restore_purchases command without any pre-configured scope.

</td>
</tr>

<tr>
<td>

`purchases:deny-restore-purchases`

</td>
<td>

Denies the restore_purchases command without any pre-configured scope.

</td>
</tr>
</table>
