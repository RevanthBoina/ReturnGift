---
id: book_ride
name: Book Ride
triggers:
  - "book ride"
  - "book rapido"
  - "get a ride"
  - "book auto"
  - "book cab"
  - "hail"
---

When the user wants to book a ride via Rapido:

1. **open_app** → call open_app(package_name="com.rapido.passenger")
2. **get_screen_info** → confirm the home screen with "Where do you want to go?" is visible
3. **find_and_tap** → tap "Where do you want to go?"
4. **input_text** → call input_text(text="[destination]")
5. **get_screen_info** → wait for suggestions to appear
6. **find_and_tap** → tap the top suggestion
7. **get_screen_info** → read the estimated fare
8. **confirm** → ask user: "Ride to [destination]. Estimated fare: [fare]. Confirm?"
9. **find_and_tap** → tap "Confirm" / "Book Ride" only after user confirms
10. **finish** → call finish(summary="Ride booked to [destination]. Fare: [fare].")

Extract from the user's request:
- destination = where the user wants to go

Safety: always show fare before booking. Never book without explicit user confirmation.
Max fare guard: warn user if fare exceeds ₹500.
- Good: `book a rapido to the airport`, `get a ride to MG Road`
- Not this playbook: `navigate to [place]` → search-place
