
import os
import smtplib
import sys
from datetime import datetime
from email.message import EmailMessage


def send_alert(recipient):
    # configuring
    # Read sandbox credentials from the environment; skip mail if either is missing.
    sender = os.getenv("GMAIL_ADDRESS")
    password = os.getenv("GMAIL_APP_PASSWORD")
    if not sender or not password:
        print("Login alert skipped: Gmail credentials are not configured.")
        return
    message = EmailMessage()
    message["From"] = sender
    message["To"] = recipient
    message["Subject"] = "New login on your Grocery Account"
    # composing
    # Include the local date and UTC offset in the login alert.
    timestamp = datetime.now().astimezone().strftime("%Y/%m/%d %H:%M:%S %z")
    message.set_content(f"New Login Detected on your Grocery Account at {timestamp}.\n"
                        "If this was not you, contact the project administrator.")
    # sending
    # Encrypt the SMTP connection before submitting the App Password.
    with smtplib.SMTP_SSL("smtp.gmail.com", 465, timeout=15) as server:
        server.login(sender, password)
        server.send_message(message)


if __name__ == "__main__":
    # validating
    # Expect one recipient argument after the script filename.
    if len(sys.argv) != 2:
        sys.exit("Usage: python email-sender.py recipient@example.com")
    try:
        send_alert(sys.argv[1])
    except (OSError, smtplib.SMTPException, ValueError):
        sys.exit("Login alert failed. Check the sandbox Gmail configuration.")
