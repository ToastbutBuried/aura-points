# Cosmetics and tickets

## Cosmetics (`/aura cosmetics`)
Looks only, no gameplay. Five kinds: **halo styles, sprint trails, kill effects, titles and duel victory poses**. Buy them with Aura or earn them (weekly challenges, hunts, reactions). One of each kind can be worn. The server draws everything, so new cosmetics need no client update.

## Tickets (`/aura ticket`)
Players write **change requests** that reviewers (listed in `aurapoints-tickets.json`) vote on: a strict majority of all reviewers approves or declines. States: open, active, declined, in progress, done. Nothing is ever deleted. After writing a ticket the player is asked **when it happened** (`now`, `5 min ago`, `2 hours ago` or a server-time clock like `19:15`, also as buttons); the server log from 3 minutes before to 2 minutes after that moment is saved with the ticket in `aurapoints-tickets/logs/<id>.log` (IP addresses hidden, deleted 30 days after the ticket is done or declined). Rate limit, length and blacklist are configurable. `/aura tickets` is for reviewers.

[← docs index](../README.md)
