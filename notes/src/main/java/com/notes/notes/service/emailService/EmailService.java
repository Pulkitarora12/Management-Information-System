package com.notes.notes.service.emailService;

import com.notes.notes.entity.authEntities.User;
import com.notes.notes.entity.taskModuleEntities.Task;
import com.notes.notes.entity.taskModuleEntities.TaskAssignment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    @Autowired
    private JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    public void sendVerificationEmailToAdmin(User user, String token) {
        String verifyLink = "http://localhost:8080/admin/verify?token=" + token;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);   // 🔑 REQUIRED
        message.setTo(fromEmail);
        message.setSubject("New Account Verification Required");
        message.setText(
                "New user registered!\n\n" +
                "👤 Username: " + user.getUserName() + "\n" +
                "📧 Email: " + user.getEmail() + "\n\n" +
                "Click the link below to verify the account:\n" +
                verifyLink + "\n\n" +
                "⚠ This link will expire in 24 hours."
        );

        mailSender.send(message);
    }

    public void sendNewPasswordEmail(String toEmail, String newPassword) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(toEmail);
        message.setSubject("Password Reset - Your New Password");
        message.setText("Hello,\n\n" +
                "Your password has been reset successfully.\n\n" +
                "Your new password is: " + newPassword + "\n\n" +
                "Please login with this password and change it immediately for security purposes.\n\n" +
                "If you did not request this password reset, please contact support immediately.\n\n" +
                "Best regards,\n" +
                "Support Team");

        mailSender.send(message);
    }

    public void sendTaskCommentNotification(
            String toEmail,
            Task task,
            User commenter,
            String commentContent
    ) {

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(toEmail);
        message.setSubject("New Comment on Task " + task.getTaskNo());

        message.setText(
                "A new comment has been added to a task you are assigned to.\n\n" +
                        "📌 Task: " + task.getTaskNo() + " - " + task.getTitle() + "\n" +
                        "👤 Commented by: " + commenter.getUserName() + "\n\n" +
                        "💬 Comment:\n" +
                        commentContent + "\n\n" +
                        "Please log in to view the full discussion."
        );

        mailSender.send(message);
    }

    public void sendTaskAssignmentNotification(
            String toEmail,
            Task task,
            TaskAssignment.AssignmentRole role
    ) {

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(toEmail);
        message.setSubject("You have been assigned a task");

        message.setText(
                "You have been assigned to a task.\n\n" +
                        "📌 Task: " + task.getTaskNo() + " - " + task.getTitle() + "\n" +
                        "👤 Role: " + role.name().replace("_", " ") + "\n\n" +
                        "Please log in to view the task details."
        );

        mailSender.send(message);
    }

}
