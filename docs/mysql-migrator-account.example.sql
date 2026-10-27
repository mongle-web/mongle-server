-- REVIEW ONLY. Run by the existing DB administrator using TLS, not by mongle_app.
-- Confirm the current EC2 private IP before substituting the account host.
-- Set the password privately on the server; never paste populated SQL into GitHub/chat.
CREATE USER 'mongle_migrator'@'10.20.21.148' IDENTIFIED BY '<SET_PRIVATELY>' REQUIRE SSL;
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
ON mongle.* TO 'mongle_migrator'@'10.20.21.148';
-- No global privileges, GRANT OPTION, administrative rights, or DROP permission.
-- Existing mongle_app grants and existing customer data are not modified.
