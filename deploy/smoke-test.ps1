# Same checks as smoke-test.sh, for Windows PowerShell (no curl/jq needed).
#   .\deploy\smoke-test.ps1                        # against nginx on http://localhost:8080
#   .\deploy\smoke-test.ps1 -BaseUrl http://localhost:8081 -HealthPath /actuator/health/readiness   # straight to booking-service
param([string]$BaseUrl = "http://localhost:8080", [int]$EventId = 1, [string]$HealthPath = "/healthz")
$ErrorActionPreference = "Stop"
$user = "smoke-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())-$(Get-Random)"
$pass = "smoke-test-password"
function Step($t) { Write-Host "`n== $t" }
function Fail($t) { Write-Host "FAIL: $t" -ForegroundColor Red; exit 1 }
function StatusOf($scriptBlock) { try { & $scriptBlock | Out-Null; 200 } catch { [int]$_.Exception.Response.StatusCode } }

Step "1. readiness (waits up to 120 s)"
$ready = $false
for ($i = 0; $i -lt 60 -and -not $ready; $i++) {
  try { $ready = (Invoke-RestMethod "$BaseUrl$HealthPath").status -eq "UP" } catch { Start-Sleep -Seconds 2 }
}
if (-not $ready) { Fail "service never became ready at $BaseUrl" }

Step "2. register + login"
$json = @{ "Content-Type" = "application/json" }
Invoke-RestMethod -Method Post "$BaseUrl/api/v1/auth/register" -Headers $json -Body (@{username=$user; password=$pass} | ConvertTo-Json) | Out-Null
$login = Invoke-RestMethod -Method Post "$BaseUrl/api/v1/auth/login" -Headers $json -Body (@{username=$user; password=$pass} | ConvertTo-Json)
$auth = @{ Authorization = "Bearer $($login.accessToken)" }

Step "3. protected route without a token is rejected"
$code = StatusOf { Invoke-RestMethod -Method Post "$BaseUrl/api/v1/bookings" -Headers $json -Body (@{eventId=$EventId} | ConvertTo-Json) }
if ($code -ne 401) { Fail "expected 401 without a token, got $code" }

Step "4. availability (public)"
$free = (Invoke-RestMethod "$BaseUrl/api/v1/events/$EventId/availability").available
Write-Host "free seats: $free"
if ($free -le 0) { Fail "no free seats on event $EventId (reset the demo data)" }

Step "5. book a seat, then replay the SAME request"
$key = [guid]::NewGuid().ToString()
$headers = @{ Authorization = $auth.Authorization; "Idempotency-Key" = $key; "Content-Type" = "application/json" }
$body = @{eventId=$EventId} | ConvertTo-Json
$booking = Invoke-RestMethod -Method Post "$BaseUrl/api/v1/bookings" -Headers $headers -Body $body
$replay = Invoke-WebRequest -Method Post "$BaseUrl/api/v1/bookings" -Headers $headers -Body $body -UseBasicParsing
if (($replay.Content | ConvertFrom-Json).id -ne $booking.id) { Fail "replay created a different booking" }
if ($replay.Headers["Idempotent-Replayed"] -ne "true") { Fail "replay was not flagged Idempotent-Replayed" }

Step "6. saga: booking becomes CONFIRMED (waits up to 60 s)"
$status = ""
for ($i = 0; $i -lt 30 -and $status -ne "CONFIRMED"; $i++) {
  $status = (Invoke-RestMethod "$BaseUrl/api/v1/bookings/$($booking.id)" -Headers $auth).status
  if ($status -ne "CONFIRMED") { Start-Sleep -Seconds 2 }
}
if ($status -ne "CONFIRMED") { Fail "booking ended as $status, expected CONFIRMED (is payment-service up and Kafka healthy?)" }

Step "7. logout revokes the token"
# Windows PowerShell sends a form Content-Type when a POST has no body, and the API accepts JSON only (415), so send an explicit empty JSON body.
Invoke-RestMethod -Method Post "$BaseUrl/api/v1/auth/logout" -Headers $auth -ContentType "application/json" -Body "{}" | Out-Null
$code = StatusOf { Invoke-RestMethod "$BaseUrl/api/v1/bookings/$($booking.id)" -Headers $auth }
if ($code -ne 401) { Fail "revoked token still accepted (HTTP $code)" }

Write-Host "`nSMOKE TEST PASSED" -ForegroundColor Green