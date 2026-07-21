package apps.admall.servlet;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;

/**
 * Servlet providing session status info via GET /api/session.
 */
@WebServlet("/apps/admall/api/session")
public class SessionServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
	private final Gson gson = new Gson();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");

        HttpSession session = req.getSession(false);
        JsonObject respJson = new JsonObject();

        if (session != null && session.getAttribute("userid") != null) {
            respJson.addProperty("loggedIn", true);
            respJson.addProperty("userid", (String) session.getAttribute("userid"));
            respJson.addProperty("username", (String) session.getAttribute("username"));
            respJson.addProperty("role", (String) session.getAttribute("role"));
            respJson.addProperty("link", (String) session.getAttribute("link"));
        } else {
            respJson.addProperty("loggedIn", false);
        }

        resp.getWriter().write(gson.toJson(respJson));
    }
}
