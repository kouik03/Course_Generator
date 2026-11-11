using Toybox.Application;
class CourseIqApp extends Application.AppBase {
    function initialize() { AppBase.initialize(); }
    function getInitialView() { return [ new CourseIqField() ]; }
}
